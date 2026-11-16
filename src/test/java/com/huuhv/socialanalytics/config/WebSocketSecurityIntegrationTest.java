package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.websocket.StompDestinations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * STOMP over SockJS trên Tomcat thật với session đăng nhập lưu MySQL: handshake phải đăng nhập, frame CONNECT phải có
 * CSRF token (đã mask từ {@code <meta name="_csrf">} hoặc raw từ cookie), client chỉ được SUBSCRIBE đích riêng của
 * user và chỉ nhận event gửi cho chính mình.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WebSocketSecurityIntegrationTest {

    private static final Pattern CSRF_META = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final long TIMEOUT_SECONDS = 10;

    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final WebSocketStompClient stompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
    /** Transport dự phòng của SockJS khi WebSocket bị chặn (dashboard.js bật xhr-streaming, xhr-polling). */
    private final WebSocketStompClient xhrStompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new RestTemplateXhrTransport())));

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    WebSocketSecurityIntegrationTest() {
        stompClient.setMessageConverter(new StringMessageConverter());
        xhrStompClient.setMessageConverter(new StringMessageConverter());
    }

    @AfterEach
    void stopClient() {
        stompClient.stop();
        xhrStompClient.stop();
    }

    @Test
    void anonymousHandshakeIsRejectedWithProblemDetail() throws Exception {
        HttpResponse<String> info = httpClient.send(HttpRequest.newBuilder(uri("/ws/info")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(info.statusCode()).isEqualTo(401);
        assertThat(info.headers().firstValue(HttpHeaders.CONTENT_TYPE)).hasValue("application/problem+json");
        assertThatThrownBy(() -> connect(null, null, new RecordingHandler()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void connectWithoutCsrfTokenIsRejected() throws Exception {
        Browser browser = loggedInBrowser();
        RecordingHandler handler = new RecordingHandler();

        CompletableFuture<StompSession> session = connect(browser.cookies(), null, handler);

        assertAccessDeniedError(handler);
        assertThatThrownBy(() -> session.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void connectWithInvalidCsrfTokenIsRejected() throws Exception {
        Browser browser = loggedInBrowser();
        RecordingHandler handler = new RecordingHandler();

        CompletableFuture<StompSession> session = connect(browser.cookies(), "forged-token", handler);

        assertAccessDeniedError(handler);
        assertThatThrownBy(() -> session.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void connectWithMaskedTokenReceivesEventsAddressedToOwnUserId() throws Exception {
        Browser browser = loggedInBrowser();
        RecordingHandler handler = new RecordingHandler();

        StompSession session = connect(browser.cookies(), browser.maskedToken(), handler)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        session.subscribe(StompDestinations.USER_METRICS_DESTINATION, handler);

        // Tên principal STOMP = id user nội bộ (StompUserIdChannelInterceptor), không phải id phía provider
        awaitDelivered(browser.userId(), handler);
        assertThat(handler.errorFrame).isNotDone();
    }

    @Test
    void xhrStreamingFallbackAlsoReceivesEventsAddressedToOwnUserId() throws Exception {
        // HandshakeHandler chỉ chạy với transport websocket — tên principal phải được gán ở frame CONNECT
        Browser browser = loggedInBrowser();
        RecordingHandler handler = new RecordingHandler();

        StompSession session = connect(xhrStompClient, browser.cookies(), browser.maskedToken(), handler)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        session.subscribe(StompDestinations.USER_METRICS_DESTINATION, handler);

        awaitDelivered(browser.userId(), handler);
        assertThat(handler.errorFrame).isNotDone();
    }

    @Test
    void connectWithRawCookieTokenIsAcceptedSoReconnectWorksAfterCookieChanged() throws Exception {
        // JS đọc cookie XSRF-TOKEN hiện tại trước mỗi lần kết nối lại (token trong <meta> có thể đã cũ)
        Browser browser = loggedInBrowser();
        RecordingHandler handler = new RecordingHandler();

        StompSession session = connect(browser.cookies(), browser.rawToken(), handler)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        session.subscribe(StompDestinations.USER_METRICS_DESTINATION, handler);

        awaitDelivered(browser.userId(), handler);
    }

    @Test
    void eventForOneUserIsNotDeliveredToAnotherUser() throws Exception {
        Browser owner = loggedInBrowser();
        Browser other = loggedInBrowser();
        RecordingHandler ownerHandler = new RecordingHandler();
        RecordingHandler otherHandler = new RecordingHandler();
        connect(owner.cookies(), owner.maskedToken(), ownerHandler).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .subscribe(StompDestinations.USER_METRICS_DESTINATION, ownerHandler);
        connect(other.cookies(), other.maskedToken(), otherHandler).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .subscribe(StompDestinations.USER_METRICS_DESTINATION, otherHandler);

        awaitDelivered(owner.userId(), ownerHandler);

        assertThat(otherHandler.messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void sendingToUserDestinationIsRejected() throws Exception {
        Browser browser = loggedInBrowser();
        RecordingHandler handler = new RecordingHandler();
        StompSession session = connect(browser.cookies(), browser.maskedToken(), handler)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        session.send("/user/" + browser.userId() + StompDestinations.METRICS_QUEUE, "forged event");

        assertAccessDeniedError(handler);
    }

    @Test
    void subscribingToBrokerQueueDirectlyIsRejected() throws Exception {
        Browser browser = loggedInBrowser();
        RecordingHandler handler = new RecordingHandler();
        StompSession session = connect(browser.cookies(), browser.maskedToken(), handler)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        session.subscribe(StompDestinations.METRICS_QUEUE, handler);

        assertAccessDeniedError(handler);
    }

    /** Scheduler của broker WebSocket không được thay chỗ scheduler chạy {@code @Scheduled} (xem AsyncConfig). */
    @Test
    void scheduledTasksKeepBootSchedulerAlongsideBrokerScheduler() {
        assertThat(applicationContext.getBean("messageBrokerTaskScheduler")).isInstanceOf(ThreadPoolTaskScheduler.class);
        assertThat(applicationContext.getBean("taskScheduler", ThreadPoolTaskScheduler.class).getThreadNamePrefix())
                .isEqualTo("scheduler-");
    }

    /** Không có RECEIPT cho SUBSCRIBE trên simple broker → gửi lại tới khi subscription đã được đăng ký. */
    private void awaitDelivered(long userId, RecordingHandler handler) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).untilAsserted(() -> {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), StompDestinations.METRICS_QUEUE, "changed");
            assertThat(handler.messages.poll(200, TimeUnit.MILLISECONDS)).isEqualTo("changed");
        });
    }

    private static void assertAccessDeniedError(RecordingHandler handler) throws Exception {
        StompHeaders error = handler.errorFrame.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Thông điệp chung — không lộ token hay chi tiết nội bộ
        assertThat(error.getFirst("message")).isEqualTo("Access denied");
    }

    /**
     * Như trình duyệt sau khi đăng nhập: mở {@code /dashboard} để nhận cookie {@code XSRF-TOKEN} và token đã mask trong
     * {@code <meta name="_csrf">}; cả 2 cookie gửi kèm handshake.
     */
    private Browser loggedInBrowser() throws IOException, InterruptedException {
        AuthenticatedSessions.LoggedInSession session = AuthenticatedSessions.create(userRepository, sessionRepository);
        HttpResponse<String> dashboard = httpClient.send(HttpRequest.newBuilder(uri("/dashboard"))
                .header("Cookie", session.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(dashboard.statusCode()).isEqualTo(200);
        String xsrfCookie = dashboard.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .map(value -> value.substring(0, value.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Dashboard response has no XSRF-TOKEN cookie"));
        Matcher meta = CSRF_META.matcher(dashboard.body());
        assertThat(meta.find()).as("dashboard renders <meta name=\"_csrf\">").isTrue();
        return new Browser(session.cookie() + "; " + xsrfCookie, meta.group(1),
                xsrfCookie.substring(xsrfCookie.indexOf('=') + 1), session.userId());
    }

    private CompletableFuture<StompSession> connect(String cookies, String csrfToken, RecordingHandler handler) {
        return connect(stompClient, cookies, csrfToken, handler);
    }

    private CompletableFuture<StompSession> connect(WebSocketStompClient client, String cookies, String csrfToken,
                                                    RecordingHandler handler) {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        if (cookies != null) {
            handshakeHeaders.add(HttpHeaders.COOKIE, cookies);
        }
        StompHeaders connectHeaders = new StompHeaders();
        if (csrfToken != null) {
            connectHeaders.add(CSRF_HEADER, csrfToken);
        }
        return client.connectAsync("http://localhost:" + port + StompDestinations.ENDPOINT,
                handshakeHeaders, connectHeaders, handler);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /**
     * @param maskedToken token trong {@code <meta name="_csrf">}
     * @param rawToken    giá trị cookie {@code XSRF-TOKEN}
     */
    private record Browser(String cookies, String maskedToken, String rawToken, long userId) {
    }

    /** Ghi lại ERROR frame (frame cấp session) và payload nhận được trên subscription. */
    private static final class RecordingHandler extends StompSessionHandlerAdapter implements StompFrameHandler {

        final CompletableFuture<StompHeaders> errorFrame = new CompletableFuture<>();
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (headers.getDestination() != null) {
                messages.add((String) payload);
            } else {
                errorFrame.complete(headers);
            }
        }
    }
}
