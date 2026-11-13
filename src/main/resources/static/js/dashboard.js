// Dashboard realtime: biểu đồ Chart.js + "Last updated time" + thống kê import của user đang đăng nhập.
// - Dữ liệu: GET /api/chart-data (đã lọc theo owner ở server). URL lấy từ data-* của #dashboard (theo context path).
// - Realtime: STOMP over SockJS, SUBSCRIBE đích riêng của user (data-metrics-destination, StompDestinations). Server
//   chỉ gửi cho user có dữ liệu vừa đổi; event là tín hiệu {type, occurredAt} → gọi lại API (debounce + jitter).
// - Lần tải đầu chạy sau khi SUBSCRIBE (1 request/trang, không lỡ event); kết nối lại → tải lại; kết nối lại thất bại
//   → giãn dần, định kỳ kiểm tra session (401 → trang login).
// - Chart tạo đúng 1 lần (new Chart); các lần sau chỉ đổi chart.data rồi chart.update().
// - Mọi dữ liệu từ server/WebSocket ghi vào DOM bằng textContent / thuộc tính, không dùng innerHTML (OWASP A05).
(function () {
    'use strict';

    const REFRESH_DEBOUNCE_MS = 500;
    // Giãn ngẫu nhiên lời gọi API: sau khi server khởi động lại, mọi dashboard kết nối lại cùng lúc
    // (không phải giá trị bảo mật, Math.random đủ)
    const REFRESH_JITTER_MS = 3000;
    const RECONNECT_DELAY_MS = 5000;
    // Kết nối lại thất bại liên tiếp → giãn dần (5s, 10s, 20s… tối đa 60s), không dồn request vào server đang lỗi
    const MAX_RECONNECT_DELAY_MS = 60000;
    // Sau ngần này lần đóng liên tiếp mà chưa CONNECT được → gọi API kiểm tra session: handshake bị 401 (session hết
    // hạn) chỉ làm socket đóng, không có ERROR frame
    const SESSION_CHECK_AFTER_FAILED_CONNECTS = 3;
    // Lần tải đầu đợi kết nối realtime (SUBSCRIBE trước rồi mới tải → không lỡ event, chỉ 1 request); quá thời gian
    // này thì tải luôn, không để trang trống
    const INITIAL_LOAD_FALLBACK_MS = 2000;
    const HEARTBEAT_MS = 10000;
    // CONNECT bị từ chối liên tiếp (token đã lấy mới mỗi lần — lỗi khác: quyền, server) → dừng, không thử lại mãi
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
        metricsDestination: root.dataset.metricsDestination,
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
    // Số lần socket đóng liên tiếp chưa vào được onConnect
    let failedConnects = 0;
    // Hẹn tải dữ liệu nếu realtime chưa kết nối kịp (null = đã tải / không chờ)
    let initialLoadTimer = null;
    // ERROR frame → Spring đóng socket ngay sau đó: giữ badge lỗi, không đổi thành "Reconnecting…"
    let closedByStompError = false;

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
        const date = new Date(instant);
        // Múi giờ server không phải tên IANA (vd -Duser.timezone=GMT+07:00) hoặc trình duyệt chưa hỗ trợ longOffset
        // → hiển thị theo giờ máy người dùng, không để lỗi định dạng làm hỏng phần còn lại của dashboard
        const formatted = formatInZone(date, zone) || formatInBrowserZone(date);
        element.textContent = formatted.date + ' ' + formatted.clock + ' ' + formatted.offset;
        element.dateTime = formatted.date + 'T' + formatted.clock + formatted.offset;
    }

    function formatInZone(date, zone) {
        try {
            const parts = {};
            new Intl.DateTimeFormat('en-CA', {
                timeZone: zone,
                year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
                timeZoneName: 'longOffset'
            }).formatToParts(date).forEach((part) => {
                parts[part.type] = part.value;
            });
            // longOffset: "GMT+07:00"; UTC là "GMT"
            const offset = parts.timeZoneName === 'GMT' ? '+00:00' : (parts.timeZoneName || '').replace('GMT', '');
            if (!/^[+-]\d{2}:\d{2}$/.test(offset)) {
                return null;
            }
            return {
                date: parts.year + '-' + parts.month + '-' + parts.day,
                clock: parts.hour + ':' + parts.minute + ':' + parts.second,
                offset: offset
            };
        } catch (e) {
            return null;
        }
    }

    function formatInBrowserZone(date) {
        const pad = (value) => String(value).padStart(2, '0');
        const minutesEast = -date.getTimezoneOffset();
        const sign = minutesEast >= 0 ? '+' : '-';
        const absolute = Math.abs(minutesEast);
        return {
            date: date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()),
            clock: pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds()),
            offset: sign + pad(Math.floor(absolute / 60)) + ':' + pad(absolute % 60)
        };
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
        // Không whitelist loại event (server có thể thêm loại mới): mọi event có type đều là tín hiệu làm mới
        if (event && typeof event.type === 'string' && event.type !== '') {
            scheduleRefresh();
        }
    }

    function startRealtime() {
        if (!window.StompJs || !window.SockJS) {
            setStatus('Realtime unavailable', 'secondary');
            return;
        }
        // activate() do connectThenLoad gọi (kèm hẹn tải dữ liệu)
        stompClient = new window.StompJs.Client({
            // Không dùng transport iframe (bị chặn bởi X-Frame-Options: DENY)
            webSocketFactory: () => new window.SockJS(urls.ws, null,
                { transports: ['websocket', 'xhr-streaming', 'xhr-polling'] }),
            // CSRF ở frame CONNECT: đọc token hiện tại trong cookie ngay trước MỖI lần kết nối (cookie có thể đã đổi
            // từ khi tải trang — đăng nhập lại ở tab khác); không có cookie thì dùng token trong <meta> (csrf.js)
            beforeConnect: () => {
                try {
                    stompClient.connectHeaders = window.currentCsrfHeaders();
                } catch (e) {
                    // stompjs không bắt lỗi ở đây (kết nối treo, không thử lại) → dùng token trong <meta>
                    stompClient.connectHeaders = window.csrfHeaders();
                }
            },
            reconnectDelay: RECONNECT_DELAY_MS,
            reconnectTimeMode: window.StompJs.ReconnectionTimeMode.EXPONENTIAL,
            maxReconnectDelay: MAX_RECONNECT_DELAY_MS,
            heartbeatIncoming: HEARTBEAT_MS,
            heartbeatOutgoing: HEARTBEAT_MS,
            onConnect: () => {
                stompErrors = 0;
                failedConnects = 0;
                closedByStompError = false;
                setStatus('Live', 'success');
                stompClient.subscribe(urls.metricsDestination, onEvent);
                if (initialLoadTimer !== null) {
                    // Lần tải đầu: đã SUBSCRIBE → tải ngay, không cần tải lại lần nữa
                    window.clearTimeout(initialLoadTimer);
                    initialLoadTimer = null;
                    refresh();
                } else {
                    // Kết nối lại (hoặc dữ liệu đã tải trước khi SUBSCRIBE): event phát lúc mất kết nối không tới
                    // client → tải lại
                    scheduleRefresh();
                }
            },
            onStompError: () => {
                stompErrors += 1;
                closedByStompError = true;
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
                if (closedByStompError || stompClient === null || !stompClient.active) {
                    return;
                }
                setStatus('Reconnecting…', 'warning');
                failedConnects += 1;
                if (failedConnects % SESSION_CHECK_AFTER_FAILED_CONNECTS === 0) {
                    // Session hết hạn → API trả 401 → dừng realtime, chuyển về trang login
                    refresh();
                }
            }
        });
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
    // Trang khôi phục từ bfcache (nút Back): kết nối lại rồi tải dữ liệu mới
    window.addEventListener('pageshow', (event) => {
        if (event.persisted && stompClient !== null) {
            stompErrors = 0;
            failedConnects = 0;
            connectThenLoad();
        }
    });

    /** Kết nối realtime, tải dữ liệu ngay sau khi SUBSCRIBE (onConnect); kết nối chậm thì tải trước. */
    function connectThenLoad() {
        window.clearTimeout(initialLoadTimer);
        initialLoadTimer = window.setTimeout(() => {
            initialLoadTimer = null;
            refresh();
        }, INITIAL_LOAD_FALLBACK_MS);
        stompClient.activate();
    }

    startRealtime();
    if (stompClient !== null) {
        connectThenLoad();
    } else {
        refresh(); // không có thư viện realtime → chỉ tải 1 lần
    }
})();
