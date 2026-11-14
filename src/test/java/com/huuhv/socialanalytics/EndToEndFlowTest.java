package com.huuhv.socialanalytics;

import com.huuhv.socialanalytics.client.soap.ExchangeRateSoapClient;
import com.huuhv.socialanalytics.config.AuthenticatedSessions;
import com.huuhv.socialanalytics.config.AuthenticatedSessions.LoggedInSession;
import com.huuhv.socialanalytics.dto.request.PostImportRow;
import com.huuhv.socialanalytics.dto.request.SocialMetricImportRow;
import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.enums.DashboardEventType;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.excel.ExcelReadResult;
import com.huuhv.socialanalytics.excel.ExcelRow;
import com.huuhv.socialanalytics.excel.GenericExcelImporter;
import com.huuhv.socialanalytics.repository.SocialAccountRepository;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.scheduler.UpdateSocialMetricsJob;
import com.huuhv.socialanalytics.service.ExchangeRateService;
import com.huuhv.socialanalytics.websocket.StompDestinations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test end-to-end (Task 7.4): app chạy trên Tomcat thật (cổng ngẫu nhiên) với MySQL Testcontainers + Flyway,
 * Artemis embedded, endpoint SOAP mock; client đi qua HTTP thật (session đăng nhập lưu MySQL, CSRF) và STOMP over
 * SockJS như trình duyệt. Một kịch bản xuyên suốt các giai đoạn của dự án:
 * <ol>
 *     <li>Dashboard render được, tỷ giá lấy qua SOAP (client → HTTP → endpoint mock của chính app).</li>
 *     <li>{@code POST /api/import-posts} (file mẫu) → transaction import → JMS {@code IMPORT_COMPLETED} →
 *     listener cộng thống kê → JMS topic → WebSocket {@code IMPORT_PROCESSED} tới đúng user →
 *     {@code GET /api/chart-data} có thống kê.</li>
 *     <li>Job crawl metrics (mock API Facebook, thread pool riêng, ShedLock) → {@code METRICS_UPDATED} → biểu đồ có số
 *     liệu + "Last updated time".</li>
 *     <li>{@code POST /api/import-metrics} (file mẫu) → {@code METRICS_UPDATED} → biểu đồ cộng snapshot đã import.</li>
 *     <li>{@code GET /api/export-report} / {@code /api/export-metrics} → đọc lại bằng importer generic (round-trip).</li>
 *     <li>User khác đăng nhập cùng lúc không nhận event nào, không thấy số liệu.</li>
 * </ol>
 * Chờ kết quả bất đồng bộ bằng Awaitility / hàng đợi có timeout, không {@code sleep}.
 * <p>
 * {@code retry-after-failure=0}: context riêng cho class này (không dùng chung cache context với test khác — tỷ giá
 * có thể đã bị cache lỗi trong context khác) và client SOAP được trỏ tới cổng ngẫu nhiên trước request đầu tiên.
 * Request dashboard chỉ đọc cache tỷ giá (job làm mới tắt trong profile test) → test gọi
 * {@link ExchangeRateService#refreshIfDue()} như {@code RefreshExchangeRateJob}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.soap.exchange-rate.retry-after-failure=0")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class EndToEndFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Pattern CSRF_META = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** Payload thăm dò (không phải loại event thật) để biết subscription đã đăng ký xong trên simple broker. */
    private static final String PROBE = "PROBE";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final WebSocketStompClient stompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
    private final GenericExcelImporter importer = new GenericExcelImporter();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private UpdateSocialMetricsJob updateSocialMetricsJob;

    @Autowired
    private ExchangeRateSoapClient exchangeRateSoapClient;

    @Autowired
    private ExchangeRateService exchangeRateService;

    EndToEndFlowTest() {
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @BeforeEach
    void pointSoapClientToRandomPort() {
        // Cấu hình mặc định trỏ :8080 — cổng thật chỉ biết sau khi Tomcat khởi động
        exchangeRateSoapClient.setDefaultUri(baseUrl() + "/services");
    }

    @AfterEach
    void stopStomp() {
        stompClient.stop();
    }

    @Test
    void importJmsStatisticsWebSocketCrawlChartAndExportFlow() throws Exception {
        // Tỷ giá: job nền làm mới cache (request dashboard chỉ đọc) — gọi trước khi đăng nhập vì loggedInBrowser() đã
        // tải /dashboard (lấy CSRF) và giữ HTML đó
        exchangeRateService.refreshIfDue();

        // --- Đăng nhập: owner có tài khoản Facebook (token còn hạn) để job crawl; other là user đối chứng ---
        Browser owner = loggedInBrowser();
        Browser other = loggedInBrowser();
        socialAccountRepository.save(SocialAccount.builder()
                .user(userRepository.getReferenceById(owner.userId()))
                .provider(Provider.FACEBOOK)
                .providerUserId("e2e-" + UUID.randomUUID())
                .accessToken("e2e-token")
                .tokenExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1))
                .build());

        // --- Dashboard: trang render được, tỷ giá đi vòng SOAP thật ---
        assertThat(owner.dashboardHtml()).contains("id=\"exchange-rate-value\">1 USD = ").contains(" VND</span>")
                .doesNotContain("Không lấy được tỷ giá");

        // --- WebSocket: cả 2 user kết nối như dashboard.js (CSRF ở frame CONNECT), SUBSCRIBE đích riêng ---
        EventQueue ownerEvents = subscribe(owner);
        EventQueue otherEvents = subscribe(other);

        // --- Import bài viết → JMS → thống kê → WebSocket ---
        JsonNode imported = postFile(owner, "/api/import-posts", "posts.xlsx", template("posts-import-template.xlsx"));
        assertThat(imported.get("totalRows").asInt()).isEqualTo(4);
        assertThat(imported.get("created").asInt()).isEqualTo(4);
        assertThat(imported.get("failed").asInt()).isZero();
        assertThat(imported.get("importId").asLong()).isPositive();

        ownerEvents.await(DashboardEventType.IMPORT_PROCESSED);
        JsonNode afterImport = getJson(owner, "/api/chart-data?days=30");
        assertThat(afterImport.get("importStatistics").get("totalImports").asLong()).isEqualTo(1);
        assertThat(afterImport.get("importStatistics").get("successRows").asLong()).isEqualTo(4);
        assertThat(afterImport.get("lastUpdatedAt").isNull()).isTrue();

        // --- Job crawl metrics (mock Facebook API) → WebSocket → biểu đồ ---
        updateSocialMetricsJob.updateSocialMetricsJob();

        ownerEvents.await(DashboardEventType.METRICS_UPDATED);
        JsonNode afterCrawl = getJson(owner, "/api/chart-data?days=30");
        assertThat(afterCrawl.get("lastUpdatedAt").isNull()).isFalse();
        assertThat(lastValue(afterCrawl, Provider.FACEBOOK, "likes")).isPositive();
        // Tài khoản Facebook không crawl bài X
        assertThat(series(afterCrawl, Provider.TWITTER).get("likes").get(29).isNull()).isTrue();

        // --- Import snapshot metric (file mẫu, tham chiếu các bài demo-* vừa import) → WebSocket → biểu đồ ---
        JsonNode metricsImported = postFile(owner, "/api/import-metrics", "metrics.xlsx",
                template("metrics-import-template.xlsx"));
        assertThat(metricsImported.get("imported").asInt()).isEqualTo(8);
        assertThat(metricsImported.get("errors")).isEmpty();

        ownerEvents.await(DashboardEventType.METRICS_UPDATED);
        // Bài X: snapshot mới nhất của demo-tw-2001 (150) + demo-tw-2002 (12), carry-forward tới hôm nay
        assertThat(lastValue(getJson(owner, "/api/chart-data?days=30"), Provider.TWITTER, "likes")).isEqualTo(162);

        // --- Export → đọc lại bằng importer generic ---
        ExcelReadResult<PostImportRow> report = importer.read(new ByteArrayInputStream(
                download(owner, "/api/export-report")), PostImportRow.class, 100, 10_000_000);
        assertThat(report.rows()).extracting(row -> row.value().getExternalId())
                .containsExactly("demo-fb-1001", "demo-fb-1002", "demo-tw-2001", "demo-tw-2002");

        ExcelReadResult<SocialMetricImportRow> metrics = importer.read(new ByteArrayInputStream(
                download(owner, "/api/export-metrics")), SocialMetricImportRow.class, 100, 10_000_000);
        // 8 snapshot import + 2 snapshot crawl (2 bài Facebook)
        assertThat(metrics.errors()).isEmpty();
        assertThat(metrics.rows()).hasSize(10);
        assertThat(metrics.rows().stream().map(ExcelRow::value)
                .filter(row -> row.getProvider() == Provider.FACEBOOK)).hasSize(7);

        // --- Cô lập dữ liệu: user khác không nhận event, không thấy số liệu ---
        assertThat(otherEvents.nextRealEvent(Duration.ofMillis(500))).isNull();
        JsonNode othersView = getJson(other, "/api/chart-data?days=30");
        assertThat(othersView.get("importStatistics").isNull()).isTrue();
        assertThat(othersView.get("lastUpdatedAt").isNull()).isTrue();
        assertThat(lastValue(othersView, Provider.TWITTER, "likes")).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] template(String name) throws IOException {
        return new ClassPathResource("excel/" + name).getContentAsByteArray();
    }

    private static JsonNode series(JsonNode chartData, Provider provider) {
        for (JsonNode series : chartData.get("series")) {
            if (provider.name().equals(series.get("provider").asString())) {
                return series;
            }
        }
        throw new AssertionError("No series for " + provider);
    }

    /** Giá trị của ngày cuối (hôm nay) trong series; {@code null} = chưa có snapshot. */
    private static Long lastValue(JsonNode chartData, Provider provider, String metric) {
        JsonNode values = series(chartData, provider).get(metric);
        JsonNode last = values.get(values.size() - 1);
        return last.isNull() ? null : last.asLong();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Như trình duyệt sau khi đăng nhập: session MySQL + mở {@code /dashboard} lấy cookie / meta CSRF. */
    private Browser loggedInBrowser() throws IOException, InterruptedException {
        LoggedInSession session = AuthenticatedSessions.create(userRepository, sessionRepository);
        Browser browser = new Browser(baseUrl(), session.cookie(), null, null, session.userId(), null);
        HttpResponse<String> dashboard = http.send(browser.request("/dashboard").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(dashboard.statusCode()).isEqualTo(200);
        String xsrfCookie = dashboard.headers().allValues(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .map(value -> value.substring(0, value.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Dashboard response has no XSRF-TOKEN cookie"));
        Matcher meta = CSRF_META.matcher(dashboard.body());
        assertThat(meta.find()).isTrue();
        return new Browser(baseUrl(), session.cookie() + "; " + xsrfCookie, meta.group(1),
                xsrfCookie.substring(xsrfCookie.indexOf('=') + 1), session.userId(), dashboard.body());
    }

    private JsonNode getJson(Browser browser, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(browser.request(path).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return json.readTree(response.body());
    }

    private byte[] download(Browser browser, String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(browser.request(path).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE)).hasValue(XLSX);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION)).hasValueSatisfying(
                value -> assertThat(value).startsWith("attachment;"));
        return response.body();
    }

    /** Multipart upload kèm CSRF (header = giá trị cookie raw, như Swagger UI / Postman). */
    private JsonNode postFile(Browser browser, String path, String filename, byte[] content)
            throws IOException, InterruptedException {
        String boundary = "e2e-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + XLSX + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(browser.request(path)
                        .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                        .header(CSRF_HEADER, browser.rawCsrfToken())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s: %s", path, response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private EventQueue subscribe(Browser browser) throws Exception {
        EventQueue events = new EventQueue();
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.COOKIE, browser.cookies());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(CSRF_HEADER, browser.maskedCsrfToken());
        StompSession session = stompClient.connectAsync(baseUrl() + StompDestinations.ENDPOINT,
                handshakeHeaders, connectHeaders, events).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        session.subscribe(StompDestinations.USER_METRICS_DESTINATION, events);
        // SUBSCRIBE không có RECEIPT trên simple broker → gửi thăm dò tới khi subscription nhận được
        await().atMost(WAIT).untilAsserted(() -> {
            messagingTemplate.convertAndSendToUser(String.valueOf(browser.userId()), StompDestinations.METRICS_QUEUE,
                    Map.of("type", PROBE));
            assertThat(events.messages.poll(200, TimeUnit.MILLISECONDS)).isNotNull();
        });
        events.messages.clear();
        assertThat(events.errorFrame).isNull();
        return events;
    }

    /**
     * @param cookies         {@code SESSION=...; XSRF-TOKEN=...}
     * @param maskedCsrfToken token trong {@code <meta name="_csrf">} (frame CONNECT, như dashboard.js)
     * @param rawCsrfToken    giá trị cookie {@code XSRF-TOKEN} (header HTTP)
     */
    private record Browser(String baseUrl, String cookies, String maskedCsrfToken, String rawCsrfToken, long userId,
                           String dashboardHtml) {

        HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header(HttpHeaders.COOKIE, cookies);
        }
    }

    /** Payload event STOMP nhận được ({@code {"type", "occurredAt"}}) — bỏ qua thăm dò còn sót. */
    private static final class EventQueue extends StompSessionHandlerAdapter implements StompFrameHandler {

        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        volatile StompHeaders errorFrame;

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return JsonNode.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (headers.getDestination() != null) {
                messages.add((JsonNode) payload);
            } else {
                errorFrame = headers;
            }
        }

        JsonNode nextRealEvent(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            JsonNode event;
            do {
                event = messages.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } while (event != null && PROBE.equals(event.get("type").asString()));
            return event;
        }

        void await(DashboardEventType expected) throws InterruptedException {
            JsonNode event = nextRealEvent(WAIT);
            assertThat(event).as("STOMP event %s", expected).isNotNull();
            assertThat(event.get("type").asString()).isEqualTo(expected.name());
            assertThat(event.get("occurredAt").asString()).endsWith("Z");
        }
    }
}
