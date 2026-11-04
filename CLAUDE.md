# 📊 Social Analytics Dashboard - Project Context & AI Coding Guidelines

> File này áp dụng cho project `social-analytics`. Nếu có quy định trùng/khác với `CLAUDE.md` ở thư mục cha (project F&B Store), **ưu tiên file này** (đặc biệt: package gốc, cấu trúc thư mục, danh sách `*ExceptionHandler`).

## 1. Mô Tả Dự Án
Ứng dụng web giúp admin tổng hợp và theo dõi lượng tương tác (likes, shares, comments, followers) từ **Facebook** & **Twitter (X)**.

Chức năng chính:
- Social Login (Facebook, Twitter) qua OAuth2, lưu access token + thông tin user.
- Import file Excel danh sách bài viết → lưu DB → publish JMS message `IMPORT_COMPLETED`.
- Background job định kỳ crawl & cập nhật metrics (mock API FB/TW), xử lý đa luồng.
- Dashboard biểu đồ Chart.js, cập nhật realtime qua WebSocket (STOMP + SockJS).
- Export báo cáo ra Excel (Apache POI) — import/export generic bằng Reflection.
- Gọi SOAP WebService mock (Spring-WS, ví dụ exchange rate).
- Bảo mật bằng Spring Security + CSRF.

## 2. Tech Stack
| Hạng mục | Công nghệ |
|---|---|
| Language | Java 25 (`<java.version>25</java.version>`) |
| Framework | Spring Boot 4.0.x (`spring-boot-starter-parent`) |
| Build | Maven (Maven Wrapper) |
| Database | MySQL 8 + Flyway (`src/main/resources/db/migration`) |
| ORM | Spring Data JPA / Hibernate |
| Security | Spring Security + `spring-boot-starter-oauth2-client` + CSRF |
| Messaging | JMS với ActiveMQ Artemis (`spring-boot-starter-artemis`) |
| Realtime | `spring-boot-starter-websocket` (STOMP), client SockJS + `@stomp/stompjs` |
| Scheduler / Async | `@Scheduled`, `@Async`, `ThreadPoolTaskExecutor` |
| Excel | Apache POI (`poi-ooxml`) |
| WebService | Spring-WS (`spring-boot-starter-webservices`) + JAXB |
| Chart | Chart.js |
| API Docs | springdoc-openapi (bản tương thích Boot 4, dòng 3.x) |
| Testing | JUnit 5 + Mockito + MockMvc + `@DataJpaTest` |
| Logging | SLF4J + Logback (`logback-spring.xml`) |
| Frontend | Thymeleaf + Thymeleaf Layout Dialect + Bootstrap 5 + Vanilla JS |
| Tiện ích | Lombok |

### Lưu ý riêng của Spring Boot 4
- Boot 4 tách auto-configuration thành module nhỏ: Flyway cần `spring-boot-starter-flyway` (+ `flyway-mysql`), không chỉ `flyway-core`. Test slice (`@WebMvcTest`, `@DataJpaTest`) cần starter test tương ứng của module — nếu annotation không resolve được, kiểm tra dependency trước khi sửa code.
- `@MockBean` / `@SpyBean` đã bị loại bỏ → dùng `@MockitoBean` / `@MockitoSpyBean` (`org.springframework.test.context.bean.override.mockito`).
- Jackson 3 là mặc định (package `tools.jackson.*`); annotation `com.fasterxml.jackson.annotation.*` vẫn dùng được.
- Toàn bộ dùng `jakarta.*` (Servlet, Persistence, Validation, JAXB) — không dùng `javax.*`.

## 3. Lệnh Cơ Bản (Commands)
Windows dùng `mvnw.cmd`, Mac/Linux dùng `./mvnw`:
- Chạy dự án: `./mvnw spring-boot:run` (profile dev: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`)
- Build JAR bỏ qua test: `./mvnw clean package -DskipTests`
- Chạy toàn bộ test: `./mvnw test`
- Chạy 1 class test: `./mvnw test -Dtest=PostServiceTest`
- Sinh class JAXB từ XSD/WSDL: `./mvnw generate-sources`
- Docker (file `docker-compose.yaml` + `Dockerfile` ở root; trước tiên copy `.env.example` → `.env`, điền mật khẩu):
  - Chạy toàn bộ (MySQL + Artemis + app): `docker compose up -d --build`
  - Chỉ hạ tầng, app chạy bằng JDK local: `docker compose up -d mysql artemis` (app tự đọc `.env` qua `spring.config.import`)
  - Chạy Maven trong container JDK 25, không cần JDK local: `docker compose run --rm maven test` (thay `test` bằng goal bất kỳ)
  - Trong mạng Docker, app/maven kết nối `mysql:3306`, `artemis:61616`; `DB_PORT` trong `.env` chỉ là port mở ra máy host.
- Swagger UI: `http://localhost:8080/swagger-ui.html` — chỉ dùng trên trình duyệt, **không** hardcode trong code/template.

## 4. Kiến Trúc Mã Nguồn
Kiến trúc 3 lớp, package gốc **`com.huuhv.socialanalytics`**:

```
com.huuhv.socialanalytics
├── config/          # SecurityConfig, AsyncConfig, SchedulingConfig, JmsConfig, WebSocketConfig, WebServiceConfig, OpenApiConfig
├── controller/
│   ├── web/         # Thymeleaf: /login, /dashboard, /posts ...
│   └── api/         # REST: /api/posts, /api/metrics, /api/import-posts, /api/export-report, /api/chart-data
├── dto/
│   ├── request/
│   └── response/
├── entity/          # User, SocialAccount, Post, SocialMetric, ImportHistory ...
├── enums/           # Provider (FACEBOOK, TWITTER), ImportStatus, Role ...
├── repository/
├── service/         # Toàn bộ business logic (interface + impl)
├── client/
│   ├── social/      # SocialPlatformClient + FacebookMockClient, TwitterMockClient
│   └── soap/        # Spring-WS client (WebServiceGatewaySupport)
├── scheduler/       # UpdateSocialMetricsJob (@Scheduled)
├── messaging/       # JMS producer, listener, message DTO, hằng số tên queue
├── websocket/       # Publisher gửi event qua SimpMessagingTemplate
├── excel/           # @ExcelColumn, GenericExcelImporter<T>, GenericExcelExporter<T> (Reflection)
├── exception/       # ApiExceptionHandler, WebExceptionHandler, exception nghiệp vụ
└── util/
```

Luồng chuẩn: `Controller → Service → Repository`. Scheduler, JMS listener, WebSocket publisher cũng chỉ gọi **Service**, không gọi thẳng Repository.

## 5. Quy Chuẩn Code (BẮT BUỘC)

### 5.1. Dependency Injection
- Luôn dùng Constructor Injection: `@RequiredArgsConstructor` + `private final`.
- **Không** dùng `@Autowired` trên field.

### 5.2. Entity & Database
- Tên bảng số nhiều, snake_case (`users`, `social_accounts`, `posts`, `social_metrics`).
- `@Entity` + `@Table(name = "...")`; Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`. **Không** dùng `@Data` trên Entity.
- Mọi quan hệ để `FetchType.LAZY` (kể cả `@ManyToOne`, vì mặc định của nó là EAGER).
- Schema chỉ thay đổi qua Flyway migration (`V{n}__mo_ta.sql`), `spring.jpa.hibernate.ddl-auto=validate`. Không sửa migration đã commit — tạo version mới.
- `SocialMetric` lưu dạng **snapshot theo thời gian** (mỗi lần crawl là 1 bản ghi có `collected_at`) để vẽ biểu đồ xu hướng; truy vấn "metric mới nhất" viết query riêng.
- Thêm index cho các cột lọc/tra cứu thường xuyên (`posts.external_id` + `provider` unique, `social_metrics(post_id, collected_at)`).

### 5.3. Logic & Tối ưu
- Thin Controller: validate (`@Valid`), gọi Service, trả DTO/View. Không đặt loop/tính toán trong Controller.
- **Không** dùng Entity để nhận/trả dữ liệu ở View/API — luôn qua DTO.
- Dùng `@Query` với `LEFT JOIN FETCH` khi cần dữ liệu quan hệ để tránh N+1; dữ liệu tổng hợp cho chart dùng projection/DTO query (`SELECT new ...`), không load entity rồi cộng trong Java.
- Tìm 1 bản ghi trả `Optional<T>`.
- `@Transactional` đặt ở Service; method chỉ đọc dùng `@Transactional(readOnly = true)`.

### 5.4. Xử Lý Lỗi
- REST (`controller/api`): `ApiExceptionHandler` (`@RestControllerAdvice`) trả `ProblemDetail` (RFC 9457).
- Web (`controller/web`): `WebExceptionHandler` (`@ControllerAdvice`) hoặc `RedirectAttributes` với flash message thân thiện.
- Khi thêm exception nghiệp vụ mới, sửa đúng handler theo package của controller.
- Không trả stack trace / thông báo lỗi nội bộ ra client.

### 5.5. Logging
- Dùng `@Slf4j` (Lombok). Placeholder `{}`, không nối chuỗi.
- Log lỗi kèm exception: `log.error("Crawl failed for post {}", postId, e)`.
- **Không bao giờ log** access token, client secret, cookie, CSRF token.
- Job/listener/async task đặt MDC (`jobId`, `importId`) để trace log đa luồng; nhớ `MDC.clear()` trong `finally`.
- `logback-spring.xml`: console + file theo ngày (`${LOG_PATH:logs}/social-analytics.yyyy-MM-dd.i.log`, 10MB/file, giữ 30 ngày); profile `test` chỉ log console. Pattern đã in sẵn `jobId`/`importId` và bỏ CR/LF trong message — thêm khoá MDC mới thì cập nhật `LOG_PATTERN`. Mức log theo package chỉnh ở `application*.yml`.

### 5.6. Bảo Mật — OWASP Secure Coding
Mọi code phải tuân theo **OWASP Secure Coding Practices** và **OWASP Top 10 (bản 2025)**. Khi viết hoặc review code, kiểm tra theo các nhóm dưới đây. Nếu một yêu cầu tính năng buộc phải làm trái quy tắc nào, dừng lại và nêu rõ rủi ro trước khi code.

**A01 – Broken Access Control**
- Mặc định từ chối: `anyRequest().authenticated()`; chỉ `permitAll()` tường minh cho `/login`, `/oauth2/**`, static resource. Swagger và Actuator không public ở môi trường production.
- Phân quyền theo role bằng `@PreAuthorize` ở tầng Service/Controller (cần `@EnableMethodSecurity`).
- Chống IDOR: với mọi thao tác theo `id` (post, metric, import, export), Service phải kiểm tra bản ghi thuộc về user đang đăng nhập. Không tin `userId` gửi từ client — lấy từ `SecurityContext`.
- Logout dùng POST (có CSRF). Redirect sau login/logout chỉ đi tới đường dẫn nội bộ, không redirect theo tham số do người dùng truyền vào (open redirect).

**A02 – Security Misconfiguration**
- Bật security header: `Content-Security-Policy` (chỉ cho phép `'self'` và các CDN đang dùng), `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'`; HSTS khi chạy HTTPS.
- Cookie session: `HttpOnly`, `Secure` (khi có HTTPS), `SameSite=Lax`; giữ cơ chế session fixation protection mặc định.
- Production: `server.error.include-stacktrace=never`, `include-message=never`; tắt `spring.jpa.show-sql`; Actuator chỉ expose `health`, `info`.
- CORS: không dùng `allowedOrigins("*")` kèm credentials; chỉ cấu hình khi thực sự cần.

**A03 – Software Supply Chain Failures**
- Dependency có version cố định (qua BOM của Spring Boot hoặc khai báo tường minh), không dùng version range/SNAPSHOT.
- Định kỳ quét lỗ hổng dependency (OWASP Dependency-Check Maven plugin hoặc Dependabot); không thêm thư viện không rõ nguồn gốc.
- Thư viện JS lấy từ CDN phải pin version và có `integrity` (SRI) + `crossorigin`, hoặc dùng WebJars.

**A04 – Cryptographic Failures**
- Không tự viết thuật toán mã hoá. Mã hoá access token bằng AES-GCM (IV ngẫu nhiên cho mỗi lần mã hoá), key lấy từ biến môi trường hoặc secret manager, không hardcode.
- Giá trị ngẫu nhiên phục vụ bảo mật dùng `SecureRandom`, không dùng `Random`/`Math.random()`.
- Không đưa dữ liệu nhạy cảm vào URL query string (vì sẽ bị ghi vào log/history).

**A05 – Injection**
- SQL/JPQL: chỉ dùng tham số (`:param`, Criteria API, Spring Data derived query). **Cấm** nối chuỗi input vào query, kể cả `ORDER BY` — sort field phải map qua whitelist.
- XSS: Thymeleaf dùng `th:text`; chỉ dùng `th:utext` khi dữ liệu đã được sanitize và có lý do rõ ràng. JS chèn dữ liệu vào DOM dùng `textContent`, không dùng `innerHTML` với dữ liệu từ server/người dùng (bao gồm nội dung post import từ Excel và payload WebSocket).
- XXE: parser XML (Spring-WS, JAXB, XSD) phải tắt DTD/external entity. Không tự tạo `DocumentBuilderFactory`/`XMLInputFactory` mà không cấu hình secure processing.
- Log injection: loại bỏ ký tự CR/LF khỏi input của người dùng trước khi ghi log.
- Không dùng `Runtime.exec`/`ProcessBuilder` hay SpEL với input của người dùng.

**A06 – Insecure Design**
- Validate input theo whitelist bằng Bean Validation (`@NotBlank`, `@Size`, `@Pattern`, `@Positive`...) trên DTO, kiểm tra lại ở Service với các quy tắc nghiệp vụ.
- Chống mass assignment: chỉ bind vào DTO có đúng các field cho phép; Entity không bao giờ được bind trực tiếp.
- Giới hạn tài nguyên: phân trang có `size` tối đa (vd 100), giới hạn số dòng mỗi file import, giới hạn tần suất import/export cho mỗi user.
- Upload file: kiểm tra phần mở rộng **và** nội dung thực tế (magic bytes của `.xlsx`), giới hạn dung lượng, giữ cấu hình chống zip bomb mặc định của POI (`ZipSecureFile`), không lưu file theo tên gốc do người dùng đặt.
- SSRF: URL bài viết đọc từ Excel chỉ được chấp nhận nếu thuộc domain whitelist (`facebook.com`, `x.com`, `twitter.com`); không gọi HTTP tới URL tuỳ ý do người dùng cung cấp.

**A07 – Authentication Failures**
- Chỉ đăng nhập qua OAuth2. Giữ nguyên tham số `state` và PKCE do Spring Security xử lý; không tự viết luồng OAuth.
- Kiểm tra `expires_at` của access token trước khi gọi API mạng xã hội; khi token hết hạn hoặc bị thu hồi, xử lý như lỗi nghiệp vụ, không crash job.
- Đặt session timeout hợp lý; huỷ session khi logout.

**A08 – Software or Data Integrity Failures**
- JMS: chỉ gửi message dạng JSON (TextMessage). **Không** dùng `ObjectMessage`/Java deserialization; nếu buộc phải dùng thì giới hạn trusted packages.
- Không deserialize dữ liệu không tin cậy bằng `ObjectInputStream`; Jackson không bật default typing.
- Dữ liệu đọc từ Excel, SOAP response hay mock API đều là dữ liệu không tin cậy — validate trước khi lưu.

**A09 – Security Logging and Alerting Failures**
- Log các sự kiện bảo mật: login thành công/thất bại, truy cập bị từ chối (403), CSRF không hợp lệ, import/export (ai, lúc nào, bao nhiêu dòng), message vào DLQ.
- Log không chứa token, secret, cookie, dữ liệu cá nhân không cần thiết (xem 5.5).

**A10 – Mishandling of Exceptional Conditions**
- Fail closed: khi có lỗi trong kiểm tra quyền/xác thực thì từ chối, không cho qua.
- Không nuốt exception (`catch` rỗng); hoặc xử lý, hoặc log rồi ném lại.
- Đóng tài nguyên bằng try-with-resources (`Workbook`, `InputStream`, `SXSSFWorkbook.dispose()`).
- Mọi lời gọi ra ngoài (SOAP, mock API, broker) phải có connect/read timeout; lỗi một phần không được làm hỏng trạng thái dữ liệu (dùng transaction đúng phạm vi).
- Response lỗi chỉ chứa thông điệp chung và mã lỗi; chi tiết kỹ thuật chỉ ghi vào log.

## 6. Hướng Dẫn Theo Từng Mảng

### 6.1. Security, CSRF & OAuth2 Login
- **Giữ CSRF bật** cho cả web lẫn API (app dùng session sau OAuth2 login). Không `csrf.disable()` để "cho nhanh".
- Form Thymeleaf dùng `th:action="@{...}"` để tự chèn hidden `_csrf`. JS `fetch`/upload đọc token từ `<meta name="_csrf">` + `<meta name="_csrf_header">` trong layout và gửi kèm header.
- Swagger UI gọi API POST/PUT/DELETE cần bật `springdoc.swagger-ui.csrf.enabled=true`.
- Facebook có sẵn trong `CommonOAuth2Provider` (chỉ cần `client-id`, `client-secret`, `scope`). **Twitter (X) không có sẵn** → khai báo `provider.twitter` thủ công (authorization-uri, token-uri, user-info-uri `https://api.x.com/2/users/me`), dùng OAuth 2.0 Authorization Code + PKCE; response user info nằm trong `data` nên cần custom `OAuth2UserService` để map.
- Redirect URI: `{baseUrl}/login/oauth2/code/{registrationId}` — phải khớp chính xác với cấu hình trên developer console.
- Sau login thành công: `CustomOAuth2UserService` upsert `User` + `SocialAccount` (provider, provider_user_id, access token, expires_at), rồi redirect về `/dashboard`.
- Client ID/secret lấy từ biến môi trường (`FACEBOOK_CLIENT_ID`, `FACEBOOK_CLIENT_SECRET`, `TWITTER_CLIENT_ID`, `TWITTER_CLIENT_SECRET`). **Không commit secret** vào `application*.yml`.
- Access token lưu DB qua `AttributeConverter` mã hoá (key từ biến môi trường); không trả token trong bất kỳ response nào.
- WebSocket: endpoint STOMP cũng phải yêu cầu đăng nhập; client gửi CSRF token trong header `CONNECT`.

### 6.2. Excel Import/Export (Apache POI + Reflection)
- Annotation `@ExcelColumn(name = "...", order = n)` đánh dấu field trên **DTO** (không phải Entity).
- `GenericExcelImporter<T>` đọc header → map cột vào field bằng Reflection, convert kiểu (String, Long, Integer, LocalDate, LocalDateTime, Enum). Cache metadata field theo class (`ConcurrentHashMap<Class<?>, List<FieldMeta>>`) để không reflect lại mỗi dòng.
- `GenericExcelExporter<T>` sinh header + dòng từ field có `@ExcelColumn`; dùng `SXSSFWorkbook` cho dữ liệu lớn và `dispose()` sau khi ghi.
- Import: giới hạn kích thước file (`spring.servlet.multipart.max-file-size`), chỉ nhận `.xlsx`, validate từng dòng và trả về danh sách lỗi theo số dòng thay vì fail cả file khi lỗi lẻ.
- Export: escape giá trị bắt đầu bằng `=`, `+`, `-`, `@` để chống CSV/Formula Injection.
- API: `POST /api/import-posts` (multipart), `GET /api/export-report` (trả `Content-Disposition: attachment`).

### 6.3. Background Job & Multithreading
- `@EnableScheduling`, `@EnableAsync` đặt trong class config riêng, không đặt ở Application class.
- Khai báo bean `ThreadPoolTaskExecutor` có tên (vd `socialCrawlerExecutor`): core/max pool, queue capacity, `threadNamePrefix`, `RejectedExecutionHandler`, `setWaitForTasksToCompleteOnShutdown(true)`. Gọi `@Async("socialCrawlerExecutor")` — không dùng executor mặc định.
- `updateSocialMetricsJob` chạy mỗi 1 giờ: `@Scheduled(cron = "${app.jobs.update-metrics.cron:0 0 * * * *}")` — cron để trong config để dev có thể chạy nhanh hơn.
- Xử lý nhiều tài khoản song song bằng `CompletableFuture` trên executor trên; lỗi 1 tài khoản không được làm dừng cả job (catch + log từng task).
- Lưu ý bẫy `@Async`/`@Transactional`: gọi nội bộ trong cùng class (self-invocation) **không** qua proxy → tách ra bean khác.
- Exception trong `@Async void` phải được xử lý qua `AsyncUncaughtExceptionHandler`.
- Mock API: interface `SocialPlatformClient` với implementation mock trả số liệu ngẫu nhiên có xu hướng tăng; chọn implementation theo `Provider`. Giữ interface đủ sạch để sau này thay bằng client thật.
- Lưu `last_updated_at` sau mỗi lần job chạy để hiển thị "Last updated time" trên dashboard.

### 6.4. JMS & Queue (ActiveMQ Artemis)
- Tên queue/destination là hằng số tập trung trong `messaging/` (vd `QueueNames.IMPORT_COMPLETED`), không hardcode string rải rác.
- Message là DTO/record serialize JSON (`MappingJackson2MessageConverter` hoặc converter Jackson 3 tương ứng) — không gửi Entity.
- Publish `IMPORT_COMPLETED` **sau khi transaction import commit** (`@TransactionalEventListener(phase = AFTER_COMMIT)` → producer), tránh listener đọc dữ liệu chưa commit.
- Listener (`@JmsListener`) phải **idempotent** (có thể nhận lại message khi retry) — kiểm tra theo `importId`.
- Retry & DLQ: cấu hình redelivery (`max-delivery-attempts`, `redelivery-delay`) và dead-letter address trên Artemis; có listener/log riêng cho DLQ.
- Dev có thể dùng `spring.artemis.mode=embedded` để không cần cài broker.

### 6.5. WebSocket + Chart.js
- STOMP endpoint `/ws` (bật SockJS), app prefix `/app`, broker prefix `/topic`.
- Sau khi job cập nhật metrics hoặc import xong → Service gọi publisher gửi event lên `/topic/metrics` (payload là DTO nhỏ, không gửi cả dataset lớn).
- Frontend: nhận event → gọi lại `GET /api/chart-data` hoặc cập nhật trực tiếp `chart.data` rồi `chart.update()`. Không tạo lại `new Chart(...)` mỗi lần.
- `/api/chart-data` trả dữ liệu đã tổng hợp sẵn từ Service (theo provider, theo ngày...).

### 6.6. SOAP WebService (Spring-WS)
- Có thể tự host 1 SOAP endpoint mock (`@Endpoint`, XSD trong `src/main/resources/xsd/`) và dùng client `WebServiceGatewaySupport` để gọi lại — đủ minh hoạ cả produce lẫn consume.
- Class JAXB sinh từ XSD qua Maven plugin vào `target/generated-sources` — **không sửa tay, không commit** class sinh ra.
- URL endpoint SOAP để trong config (`app.soap.exchange-rate.url`), có timeout.

### 6.7. Frontend (Thymeleaf)
- Layout dùng Thymeleaf Layout Dialect (`templates/layout/base.html`).
- Link luôn dùng `@{/duong-dan}`, không hardcode domain/`http://localhost:8080`.
- Thư viện JS/CSS (Bootstrap, Chart.js, SockJS, STOMP) load từ CDN có version cố định hoặc WebJars; JS riêng để trong `static/js/`, không viết script dài inline trong template.

## 7. Testing
- Cấu trúc test mirror package của `src/main`; tên class `XxxTest` (unit) / `XxxIT` (integration).
- Service: JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`), không khởi động Spring context.
- Controller: `@WebMvcTest` + `MockMvc` + `@MockitoBean` cho Service; test có POST phải kèm `.with(csrf())` và user giả lập (`@WithMockUser` hoặc `oauth2Login()`).
- Repository: `@DataJpaTest` cho query custom (`@Query`, projection).
- Excel: test importer/exporter với workbook tạo trong bộ nhớ (round-trip export → import).
- Integration test cuối: `@SpringBootTest` chạy luồng import → JMS → cập nhật thống kê → WebSocket event. Ưu tiên Testcontainers (MySQL) nếu có Docker, vì migration viết cho MySQL.
- Test bảo mật bắt buộc cho mỗi endpoint mới: chưa đăng nhập → 401/redirect login; thiếu CSRF → 403; truy cập tài nguyên của user khác → 403/404 (IDOR); input không hợp lệ → 400. Với Excel: file sai định dạng, quá dung lượng, chứa công thức (`=...`).
- Mỗi tính năng mới phải có test; chạy `./mvnw test` pass trước khi tạo PR.

## 8. Cấu Hình & Môi Trường
- Dùng `application.yml` + `application-dev.yml` / `application-test.yml`. Giá trị nhạy cảm dùng `${ENV_VAR}`.
- `application.yml` là cấu hình an toàn cho production (không show-sql, không trả stacktrace/message lỗi); `dev` chỉ tăng mức log; test dùng `@ActiveProfiles("test")`.
- Cấu hình riêng của app gom dưới prefix `app.*` và bind bằng `@ConfigurationProperties` (record), không rải `@Value`. Tất cả nằm trong `config/AppProperties` (nested record theo nhóm, có Bean Validation + `@DefaultValue` để fail fast khi sai cấu hình); thêm nhóm mới thì thêm nested record + giá trị trong `application.yml` + test binding trong `AppPropertiesTest`.
- `.env`, file Excel mẫu chứa dữ liệu thật, thư mục `logs/` phải nằm trong `.gitignore`.

## 9. Quy Trình Git
- Nhánh chính: `main`; nhánh phát triển: `develop`. Tạo nhánh tính năng từ `develop` (`feature/<ticket>-<mo-ta>`), PR về `develop`.
- PR theo template `.github/pull_request_template.md` (điền Redmine ticket, Database Changes, How to Test).
- Issue theo template trong `.github/ISSUE_TEMPLATE/`.

## 10. Lộ Trình & Trạng Thái
Cập nhật checkbox khi hoàn thành từng giai đoạn để AI biết phạm vi hiện tại:
- [ ] **1. Khởi tạo project**: cấu trúc nền, config, logging, JPA + MySQL + Flyway, entity `User`/`Post`/`SocialMetric`, CRUD `/api/posts`, `/api/metrics`, Swagger.
- [ ] **2. Unit Test + Excel**: test `PostService`, `MetricService`, controller; `/api/import-posts`, `/api/export-report`; Reflection mapping.
- [ ] **3. Security + CSRF + Social Login**: Facebook & Twitter login → `/dashboard`, lưu token & user.
- [ ] **4. Background Job & Multithread**: `updateSocialMetricsJob` mỗi giờ, mock API, màn hình "Last updated time".
- [ ] **5. JMS**: `IMPORT_COMPLETED` → listener cập nhật thống kê tổng hợp, retry + DLQ.
- [ ] **6. WebSocket + Chart**: broadcast khi metrics cập nhật, Chart.js realtime, `/api/chart-data`.
- [ ] **7. Spring-WS + Reflection nâng cao + Tổng kết**: SOAP mock, generic export, integration test, đóng gói & demo end-to-end.

Chỉ triển khai trong phạm vi giai đoạn đang làm; không tự thêm tính năng của giai đoạn sau khi chưa được yêu cầu.
