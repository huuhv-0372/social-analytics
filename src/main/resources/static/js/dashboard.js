// Dashboard realtime: biểu đồ Chart.js + "Last updated time" + thống kê import của user đang đăng nhập.
// - Dữ liệu: GET /api/chart-data (đã lọc theo owner ở server). URL lấy từ data-* của #dashboard (theo context path).
// - Realtime: STOMP over SockJS, SUBSCRIBE /topic/metrics. Event chỉ là tín hiệu {type, occurredAt} gửi tới mọi user
//   → nhận event thì gọi lại API (debounce + jitter để mọi dashboard không cùng gọi 1 lúc).
// - Chart tạo đúng 1 lần (new Chart); các lần sau chỉ đổi chart.data rồi chart.update().
// - Mọi dữ liệu từ server/WebSocket ghi vào DOM bằng textContent / thuộc tính, không dùng innerHTML (OWASP A05).
(function () {
    'use strict';

    const METRICS_TOPIC = '/topic/metrics';
    const EVENT_TYPES = new Set(['METRICS_UPDATED', 'IMPORT_PROCESSED']);
    const REFRESH_DEBOUNCE_MS = 500;
    // Event broadcast tới mọi dashboard cùng lúc → giãn ngẫu nhiên lời gọi API (không phải giá trị bảo mật, Math.random đủ)
    const REFRESH_JITTER_MS = 3000;
    const RECONNECT_DELAY_MS = 5000;
    const HEARTBEAT_MS = 10000;
    // CONNECT bị từ chối liên tiếp (CSRF token cũ sau khi đăng nhập lại ở tab khác...) → dừng, không thử lại mãi
    const MAX_STOMP_ERRORS = 3;
    const PROVIDERS = {
        FACEBOOK: { label: 'Facebook', color: '#1877f2' },
        TWITTER: { label: 'X (Twitter)', color: '#212529' }
    };
    const UNKNOWN_PROVIDER_COLOR = '#6c757d';
    const ERROR_MESSAGE = 'Không tải được dữ liệu dashboard. Dữ liệu sẽ được tải lại khi có cập nhật mới.';

    const root = document.getElementById('dashboard');
    if (!root) {
        return;
    }
    const urls = {
        chartData: root.dataset.chartDataUrl,
        ws: root.dataset.wsUrl,
        login: root.dataset.loginUrl
    };
    const byId = (id) => document.getElementById(id);
    const metricSelect = byId('metric-select');
    const daysSelect = byId('days-select');
    const numberFormat = new Intl.NumberFormat('en-US');

    let chart = null;
    let lastData = null;
    let refreshTimer = null;
    let refreshing = false;
    let refreshPending = false;
    let stompClient = null;
    let stompErrors = 0;
    let connectedBefore = false;

    // ---------------------------------------------------------------- dữ liệu

    async function refresh() {
        if (refreshing) {
            // Đang gọi API: gọi thêm đúng 1 lần sau khi xong (gộp các yêu cầu dồn dập)
            refreshPending = true;
            return;
        }
        refreshing = true;
        try {
            const url = new URL(urls.chartData, window.location.origin);
            url.searchParams.set('days', daysSelect.value);
            const response = await fetch(url, {
                credentials: 'same-origin',
                headers: { Accept: 'application/json' }
            });
            if (response.status === 401) {
                // Session hết hạn / đã logout ở tab khác
                stopRealtime();
                window.location.assign(urls.login);
                return;
            }
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            lastData = await response.json();
            render(lastData);
            showError(null);
        } catch (e) {
            console.warn('Dashboard refresh failed', e);
            showError(ERROR_MESSAGE);
        } finally {
            refreshing = false;
            if (refreshPending) {
                refreshPending = false;
                refresh();
            }
        }
    }

    function scheduleRefresh() {
        if (refreshTimer !== null) {
            return; // đã hẹn lịch — gộp các event tới gần nhau
        }
        const delay = REFRESH_DEBOUNCE_MS + Math.floor(Math.random() * REFRESH_JITTER_MS);
        refreshTimer = window.setTimeout(() => {
            refreshTimer = null;
            refresh();
        }, delay);
    }

    // ---------------------------------------------------------------- hiển thị

    function render(data) {
        renderChart(data);
        renderLastUpdated(data.lastUpdatedAt, data.zone);
        renderImportStatistics(data.importStatistics, data.zone);
    }

    function datasetsFor(data, metric) {
        return data.series.map((series) => {
            const provider = PROVIDERS[series.provider] || { label: String(series.provider), color: UNKNOWN_PROVIDER_COLOR };
            return {
                label: provider.label,
                data: series[metric],
                borderColor: provider.color,
                backgroundColor: provider.color,
                tension: 0.2,
                // null = provider chưa có dữ liệu ngày đó → để trống, không vẽ thành 0
                spanGaps: false
            };
        });
    }

    function renderChart(data) {
        if (!window.Chart) {
            showError(ERROR_MESSAGE);
            return;
        }
        const metric = metricSelect.value;
        const hasData = data.series.some((series) => series[metric].some((value) => value !== null));
        byId('chart-empty').hidden = hasData;
        byId('chart-container').hidden = !hasData;

        const datasets = datasetsFor(data, metric);
        if (chart === null) {
            chart = new window.Chart(byId('metrics-chart'), {
                type: 'line',
                data: { labels: data.labels, datasets: datasets },
                options: {
                    responsive: true,
                    aspectRatio: 2.5,
                    interaction: { mode: 'index', intersect: false },
                    scales: {
                        y: {
                            beginAtZero: true,
                            ticks: { callback: (value) => numberFormat.format(value) }
                        }
                    },
                    plugins: {
                        tooltip: {
                            callbacks: {
                                label: (context) => context.dataset.label + ': '
                                    + (context.parsed.y === null ? '—' : numberFormat.format(context.parsed.y))
                            }
                        }
                    }
                }
            });
            return;
        }
        // Cập nhật tại chỗ: giữ object dataset cũ khi cùng provider để Chart.js chỉ animate giá trị mới
        chart.data.labels = data.labels;
        datasets.forEach((dataset, index) => {
            const current = chart.data.datasets[index];
            if (current && current.label === dataset.label) {
                current.data = dataset.data;
            } else {
                chart.data.datasets[index] = dataset;
            }
        });
        chart.data.datasets.length = datasets.length;
        chart.update();
    }

    function renderLastUpdated(instant, zone) {
        const time = byId('last-updated-time');
        const empty = byId('last-updated-empty');
        setTime(time, instant, zone);
        time.hidden = !instant;
        empty.hidden = !!instant;
    }

    function renderImportStatistics(statistics, zone) {
        byId('import-statistics-values').hidden = !statistics;
        byId('import-statistics-empty').hidden = !!statistics;
        if (!statistics) {
            return;
        }
        byId('stat-total-imports').textContent = numberFormat.format(statistics.totalImports);
        byId('stat-success-rows').textContent = numberFormat.format(statistics.successRows);
        byId('stat-failed-rows').textContent = numberFormat.format(statistics.failedRows);
        setTime(byId('stat-last-import-at'), statistics.lastImportAt, zone);
    }

    /** Ghi thời điểm theo múi giờ server, cùng định dạng trang render bằng Thymeleaf: 2026-11-07 10:00:05 +07:00. */
    function setTime(element, instant, zone) {
        if (!instant) {
            element.textContent = '';
            element.removeAttribute('datetime');
            return;
        }
        const parts = {};
        new Intl.DateTimeFormat('en-CA', {
            timeZone: zone,
            year: 'numeric', month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
            timeZoneName: 'longOffset'
        }).formatToParts(new Date(instant)).forEach((part) => {
            parts[part.type] = part.value;
        });
        // longOffset: "GMT+07:00"; UTC là "GMT"
        const offset = parts.timeZoneName === 'GMT' ? '+00:00' : parts.timeZoneName.replace('GMT', '');
        const date = parts.year + '-' + parts.month + '-' + parts.day;
        const clock = parts.hour + ':' + parts.minute + ':' + parts.second;
        element.textContent = date + ' ' + clock + ' ' + offset;
        element.dateTime = date + 'T' + clock + offset;
    }

    function showError(message) {
        const alert = byId('dashboard-error');
        alert.textContent = message || '';
        alert.hidden = !message;
    }

    function setStatus(text, variant) {
        const badge = byId('realtime-status');
        badge.textContent = text;
        badge.className = 'badge text-bg-' + variant;
    }

    // ---------------------------------------------------------------- realtime

    function onEvent(message) {
        let event;
        try {
            event = JSON.parse(message.body);
        } catch (e) {
            return;
        }
        if (event && EVENT_TYPES.has(event.type)) {
            scheduleRefresh();
        }
    }

    function startRealtime() {
        if (!window.StompJs || !window.SockJS) {
            setStatus('Realtime unavailable', 'secondary');
            return;
        }
        stompClient = new window.StompJs.Client({
            // Không dùng transport iframe (bị chặn bởi X-Frame-Options: DENY)
            webSocketFactory: () => new window.SockJS(urls.ws, null,
                { transports: ['websocket', 'xhr-streaming', 'xhr-polling'] }),
            // CSRF ở frame CONNECT: token đã mask từ <meta name="_csrf"> (csrf.js)
            connectHeaders: window.csrfHeaders(),
            reconnectDelay: RECONNECT_DELAY_MS,
            heartbeatIncoming: HEARTBEAT_MS,
            heartbeatOutgoing: HEARTBEAT_MS,
            onConnect: () => {
                stompErrors = 0;
                setStatus('Live', 'success');
                stompClient.subscribe(METRICS_TOPIC, onEvent);
                if (connectedBefore) {
                    // Có thể đã lỡ event trong lúc mất kết nối
                    scheduleRefresh();
                }
                connectedBefore = true;
            },
            onStompError: () => {
                stompErrors += 1;
                if (stompErrors >= MAX_STOMP_ERRORS) {
                    stopRealtime();
                    setStatus('Realtime off — reload page', 'secondary');
                } else {
                    setStatus('Realtime error', 'danger');
                }
                // Bị từ chối vì session hết hạn → API trả 401 → chuyển về trang login
                refresh();
            },
            onWebSocketClose: () => {
                if (stompClient !== null && stompClient.active) {
                    setStatus('Reconnecting…', 'warning');
                }
            }
        });
        stompClient.activate();
    }

    function stopRealtime() {
        if (stompClient !== null) {
            stompClient.deactivate();
        }
    }

    // ---------------------------------------------------------------- khởi động

    metricSelect.addEventListener('change', () => {
        if (lastData !== null) {
            renderChart(lastData); // chỉ đổi metric hiển thị, dữ liệu đã có — không gọi API
        }
    });
    daysSelect.addEventListener('change', refresh);
    window.addEventListener('pagehide', stopRealtime);
    // Trang khôi phục từ bfcache (nút Back): kết nối lại + tải dữ liệu mới
    window.addEventListener('pageshow', (event) => {
        if (event.persisted && stompClient !== null) {
            stompErrors = 0;
            stompClient.activate();
            refresh();
        }
    });

    refresh();
    startRealtime();
})();
