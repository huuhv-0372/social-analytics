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
  - Chỉ hạ tầng, app chạy bằng JDK local: `docker compose up -d mysql artemis` (app tự đọc `.env` qua `spring.config.import`). Profile `dev` mặc định chạy Artemis **embedded** (trong JVM) — muốn dùng broker của compose thì đặt `ARTEMIS_MODE=native` trong `.env`; container `app` luôn đặt `ARTEMIS_MODE=native`.
  - Chạy Maven trong container JDK 25, không cần JDK local: `docker compose run --rm maven package -DskipTests` (thay bằng goal bất kỳ). Service `maven` **không** có Docker socket nên test Testcontainers không chạy được ở đó.
  - Toàn bộ test trong container: `docker compose run --rm maven-testcontainers test` — mount Docker socket của host (quyền tương đương root cho mọi code trong build); không thêm socket vào service `maven`.
  - Trong mạng Docker, app/maven kết nối `mysql:3306`, `artemis:61616`; `DB_PORT` trong `.env` chỉ là port mở ra máy host.
- Swagger UI: `http://localhost:8080/swagger-ui.html` — chỉ dùng trên trình duyệt, **không** hardcode trong code/template.
  - Chỉ bật ở profile `dev` (`springdoc.*.enabled` tắt trong `application.yml`, bật trong `application-dev.yml`); container `app` mặc định `SPRING_PROFILES_ACTIVE=dev`.
  - Title/version khai báo ở `config/OpenApiConfig` (không có security scheme — API xác thực bằng session cookie sau khi đăng nhập OAuth2 trên trình duyệt). Controller mới thêm `@Tag` + `@Operation(summary = ...)`, tham số `Pageable` dùng `@ParameterObject`.

## 4. Kiến Trúc Mã Nguồn
Kiến trúc 3 lớp, package gốc **`com.huuhv.socialanalytics`**:

```
com.huuhv.socialanalytics
├── config/          # SecurityConfig, AsyncConfig, SchedulingConfig, JmsConfig, WebSocketConfig, WebServiceConfig, OpenApiConfig, ClockConfig, CryptoConfig, RateLimitConfig
│   └── properties/  # AppProperties (record @ConfigurationProperties) — tách khỏi package chứa bean để service đọc cấu hình mà không import config/
├── controller/
│   ├── web/         # Thymeleaf: /login, /dashboard, /posts ...
│   └── api/         # REST: /api/posts, /api/metrics, /api/import-posts, /api/export-report, /api/chart-data
├── dto/
│   ├── request/
│   └── response/
├── entity/          # User, SocialAccount, Post, SocialMetric, ImportHistory ...
├── enums/           # Provider (FACEBOOK, TWITTER), ImportStatus, Role ...
├── repository/
├── service/         # Toàn bộ business logic — class @Service trực tiếp, không tách interface + impl
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

Service viết thẳng thành class (Spring proxy `@Transactional` bằng CGLIB, Mockito/`@MockitoBean` mock được class) — chỉ tạo interface khi thực sự có nhiều implementation (vd `SocialPlatformClient`). Không khai báo class/method `@Transactional` là `final` (CGLIB không override được). Service cần logic của service khác (vd kiểm tra quyền sở hữu) thì inject service đó, không lặp lại query.

## 5. Quy Chuẩn Code (BẮT BUỘC)

### 5.1. Dependency Injection
- Luôn dùng Constructor Injection: `@RequiredArgsConstructor` + `private final`.
- **Không** dùng `@Autowired` trên field.

### 5.2. Entity & Database
- Tên bảng số nhiều, snake_case (`users`, `social_accounts`, `posts`, `social_metrics`).
- `@Entity` + `@Table(name = "...")`; Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`. **Không** dùng `@Data` trên Entity.
- Mọi quan hệ để `FetchType.LAZY` (kể cả `@ManyToOne`, vì mặc định của nó là EAGER).
- Quan hệ chỉ map một chiều `@ManyToOne` (`Post → User`, `SocialMetric → Post`); không thêm `@OneToMany` ngược — lấy danh sách con qua repository. Không override `equals`/`hashCode` trên Entity.
- Entity có `created_at`/`updated_at` kế thừa `entity/AuditableEntity` (`@PrePersist`/`@PreUpdate` gán giờ UTC, không dùng JPA Auditing; không dùng `@CreationTimestamp` vì Hibernate sinh theo múi giờ JVM). Thời gian: Entity dùng `LocalDateTime` ↔ `DATETIME(6)` lưu giờ **UTC** — đổi qua `util/UtcDateTimes` (`toUtc(Instant)`, `toInstant(LocalDateTime)`, `now()`); không theo múi giờ JVM vì DB có thể dùng chung giữa container (UTC) và máy dev (+07). Cột so với "bây giờ" trong logic nghiệp vụ (vd `social_accounts.token_expires_at`) so sánh với `LocalDateTime.now(clock.withZone(ZoneOffset.UTC))` (bean `Clock` để test được). DTO request/response và tham số query dùng `Instant` (ISO-8601 có `Z`/offset, thiếu múi giờ → 400), `@PastOrPresent`/`@Past` trên `Instant` không cần sai số múi giờ; enum lưu `@Enumerated(EnumType.STRING)` ↔ `VARCHAR`.
- Xoá `Post` sẽ xoá snapshot `social_metrics` (FK `ON DELETE CASCADE`); `users → posts` không cascade.
- `social_accounts.last_updated_at` (V4, giờ UTC, `NULL` = chưa cập nhật): lần job crawl metrics thành công gần nhất của tài khoản — chỉ job ghi (UPDATE 1 cột); entity map `insertable = false, updatable = false` để flush của luồng khác (vd `recordLogin`) không ghi đè giá trị cũ đã nạp.
- Schema chỉ thay đổi qua Flyway migration (`V{n}__mo_ta.sql`), `spring.jpa.hibernate.ddl-auto=validate`. Không sửa migration đã commit — tạo version mới.
- `SocialMetric` lưu dạng **snapshot theo thời gian** (mỗi lần crawl là 1 bản ghi có `collected_at`) để vẽ biểu đồ xu hướng; truy vấn "metric mới nhất" viết query riêng.
- Thêm index cho các cột lọc/tra cứu thường xuyên (`posts(user_id, provider, external_id)` unique — trùng bài chỉ tính trong phạm vi 1 user để không lộ dữ liệu user khác; `social_metrics(post_id, collected_at)`).

### 5.3. Logic & Tối ưu
- Thin Controller: validate (`@Valid`), gọi Service, trả DTO/View. Không đặt loop/tính toán trong Controller.
- **Không** dùng Entity để nhận/trả dữ liệu ở View/API — luôn qua DTO.
- Dùng `@Query` với `LEFT JOIN FETCH` khi cần dữ liệu quan hệ để tránh N+1; dữ liệu tổng hợp cho chart dùng projection/DTO query (`SELECT new ...`), không load entity rồi cộng trong Java.
- Tìm 1 bản ghi trả `Optional<T>`.
- `@Transactional` đặt ở Service; method chỉ đọc dùng `@Transactional(readOnly = true)`.
- Owner lấy qua `CurrentUserService.getCurrentUser()` (không nhận `userId` từ client). Thao tác theo id dùng repository method kèm điều kiện owner (`findByIdAndUserId`, `findByIdAndPostUserId`); không thuộc user → `ResourceNotFoundException` (404).
- API danh sách nhận `Pageable` (size mặc định 20, bị chặn bởi `spring.data.web.pageable.max-page-size` trong `application.yml` — không tự viết customizer), Service lọc sort bằng `PageableUtils.withAllowedSort` (whitelist) và trả `PageResponse<T>` — không trả `Page`/`PageImpl` trực tiếp.
- URL bài viết kiểm tra bằng `SocialUrlPolicy` (https + domain whitelist khai báo trong enum `Provider`).
- Mapping Entity → DTO bằng static factory `XxxResponse.from(entity)`.

### 5.4. Xử Lý Lỗi
- REST (`controller/api`): `ApiExceptionHandler` (`@RestControllerAdvice`) trả `ProblemDetail` (RFC 9457).
- Web (`controller/web`): `WebExceptionHandler` (`@ControllerAdvice`) hoặc `RedirectAttributes` với flash message thân thiện.
- Khi thêm exception nghiệp vụ mới, sửa đúng handler theo package của controller.
- Exception nghiệp vụ hiện có: `ResourceNotFoundException` (404), `BadRequestException` (400), `DuplicateResourceException` (409), `AuthenticationRequiredException` (401), `TooManyRequestsException` (429 + `Retry-After`). Lỗi `@Valid` trả 400 kèm `errors[{field, message}]`; exception không lường trước → 500 thông điệp chung + log.
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
- Log injection: loại bỏ ký tự CR/LF khỏi input của người dùng trước khi ghi log bằng `util/LogSanitizer.stripLineBreaks` (không tự viết `replaceAll` riêng; `%replace` trong `LOG_PATTERN` chỉ là lớp thứ hai).
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
> **Đã có (Task 3.1):** `config/SecurityConfig` deny-by-default — chỉ `/login`, `/oauth2/**`, `/error`, static (`/css/**`, `/js/**`, `/images/**`, `/favicon.ico`) là public, dispatch `ERROR` luôn cho qua (`dispatcherTypeMatchers(DispatcherType.ERROR)` — giữ mã lỗi gốc khi Tomcat chuyển sang trang lỗi, không bị đè thành 401/redirect login; MockMvc không dispatch trang lỗi nên kiểm tra ở `ErrorDispatchSecurityTest` chạy Tomcat thật); `/api/**` và Swagger phải đăng nhập. Chưa đăng nhập: REST (`/api/**`, `/v3/api-docs/**`) nhận 401 `ProblemDetail` (`ApiAuthenticationEntryPoint`), trang web redirect `/login`. 403 được log qua `LoggingAccessDeniedHandler` (phân biệt lỗi CSRF), REST nhận 403 `ProblemDetail` (`ApiAccessDeniedHandler`, body chung qua `ProblemDetailResponses`), trang web dùng trang lỗi mặc định. CSRF: cookie `XSRF-TOKEN` (đọc được bằng JS) + `CsrfHeaderOrFormTokenRequestHandler` — header `X-XSRF-TOKEN` nhận cả token raw (cookie, Swagger/Postman) lẫn token đã mask (`<meta name="_csrf">`, dùng qua `static/js/csrf.js` → `csrfHeaders()`); form `th:action` gửi hidden `_csrf`. Logout `POST /logout` → `/login?logout`. Header CSP (hằng `SecurityConfig.CONTENT_SECURITY_POLICY`, chỉ `'self'` + `cdn.jsdelivr.net`, không `'unsafe-inline'` → không viết script/style inline), `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`. Cookie session `HttpOnly`, `SameSite=Lax`, `Secure` theo `SESSION_COOKIE_SECURE` (mặc định true, dev/test false), timeout 30 phút. Layout: `templates/layout/base.html` (Bootstrap 5.3.3 jsDelivr + SRI).
>
> **Đã có (Task 3.2):** `oauth2Login` với `loginPage("/login")`, đăng ký `facebook` (CommonOAuth2Provider, scope `public_profile,email`, `${FACEBOOK_CLIENT_ID}`/`${FACEBOOK_CLIENT_SECRET}` bắt buộc). Nút "Login with Facebook" → `/oauth2/authorization/facebook`. `config/OAuth2LoginResultHandler` log thành công/thất bại (provider, id user phía provider, mã lỗi — không log token/code/state) rồi luôn redirect `/dashboard` (bỏ qua saved request/tham số) hoặc `/login?error`. `/` → `/dashboard`; đã đăng nhập vào `/login` → `/dashboard`. Tên hiển thị trên layout lấy từ `controller/web/CurrentUserModelAdvice` (`${displayName}`: `AppOAuth2User.getDisplayName()` — tên đã chuẩn hoá lúc đăng nhập, giống `users.display_name`; principal khác dùng thuộc tính `name`, không thì `Authentication.getName()`).
>
> **Đã có (Task 3.3):** đăng ký `twitter` khai báo thủ công trong `application.yml` (`provider.twitter`: `https://x.com/i/oauth2/authorize`, `https://api.x.com/2/oauth2/token`, `https://api.x.com/2/users/me`; confidential client `client_secret_basic`; scope `users.read,tweet.read`; `${TWITTER_CLIENT_ID}`/`${TWITTER_CLIENT_SECRET}` bắt buộc). PKCE S256 do Spring Security 7 tự áp dụng cho mọi client (`ClientSettings.requireProofKey` mặc định `true`) — không tự viết resolver. `service/CustomOAuth2UserService` (gắn qua `userInfoEndpoint`) bọc bean `DefaultOAuth2UserService` khai báo ở `config/OAuth2ClientConfig`, gắn `service/OAuth2UserAttributes::converterFor` để bóc `data` của X; mọi provider thiếu `id` → `invalid_user_info_response` (không để `DefaultOAuth2User` ném `IllegalArgumentException` thành 500). `OAuth2ClientConfig` cũng khai báo bean `OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>` (đổi code lấy token, `oauth2Login` tự lấy từ context); cả 2 client giữ converter/error handler mặc định của Spring Security, thêm connect/read timeout `app.oauth2.*` (mặc định 5s/10s). Khởi động kiểm tra mọi registration có `client-secret` (Boot chỉ kiểm `client-id`) — secret rỗng → app không khởi động. Test `@WebMvcTest` có `SecurityConfig` phải khai báo `@MockitoBean CustomOAuth2UserService` và `@MockitoBean CurrentUserService` (bản thật phụ thuộc DB; mock trả `null` = session hợp lệ).
>
> **Đã có (Task 3.4):** chỉ còn đăng nhập OAuth2 — đã gỡ HTTP Basic, `spring.security.user.*`/`API_USER_*`, interceptor provision user và matcher Swagger tạm (Swagger UI chưa đăng nhập → redirect `/login`; 401 REST không gửi `WWW-Authenticate`). Không có `UserDetailsService` (Boot không sinh mật khẩu mặc định). Migration `V2__social_accounts.sql` (unique `(provider, provider_user_id)`, FK `users`, `access_token TEXT`, `token_expires_at`). `CustomOAuth2UserService.loadUser` → `SocialAccountService.recordLogin` (`@Transactional`): tài khoản mới tạo `User` mới (email Facebook chỉ gán khi chưa thuộc user khác — **không** gộp tài khoản theo email), tài khoản cũ cập nhật token/hạn token/tên hiển thị; tên chuẩn hoá (bỏ ký tự điều khiển, cắt 100 code point). Vi phạm unique hoặc tranh chấp khoá (`PessimisticLockingFailureException`: deadlock, lock wait timeout) do đăng nhập song song → thử lại 1 lần (transaction mới); lỗi DB khác (`DataAccessException`, `TransactionException`: không mở được transaction, commit lỗi) → `OAuth2AuthenticationException(server_error)` + log → `/login?error` (fail closed). Access token mã hoá bằng `entity/converter/AccessTokenConverter` → `util/AesGcmCipher` (AES-256-GCM, IV 12 byte `SecureRandom` mỗi lần, lưu Base64 `IV‖ciphertext‖tag`); bean `accessTokenCipher` ở `config/CryptoConfig`, key `app.token-encryption.key` ← env `TOKEN_ENCRYPTION_KEY` (Base64 32 byte, sai/thiếu → không khởi động, `toString` che key). Token không giải mã được (đã đổi key, dữ liệu hỏng) → converter trả `null` + log warn (coi như không có token, đăng nhập lại sẽ ghi đè) thay vì ném lỗi làm user không đăng nhập được; vì vậy `access_token` cho phép NULL (`V3__social_accounts_access_token_nullable.sql`) — code gọi API mạng xã hội phải kiểm tra token `null`. Converter được Hibernate tạo qua `SpringBeanContainer`, lấy cipher trễ bằng `ObjectProvider` → `@DataJpaTest` có dùng `SocialAccount` phải `@Import(CryptoConfig.class)`. `CustomOAuth2UserService.loadUser` trả principal `service/AppOAuth2User` (thuộc tính provider + `userId` nội bộ + tên hiển thị, lưu trong session) → `CurrentUserService.getCurrentUser()` kiểm tra `existsById(userId)` (1 query theo khoá chính; user đã bị xoá / DB reset → 401) rồi trả `UserRepository.getReferenceById(userId)`: không transaction — caller chỉ dùng `getId()` hoặc gán làm khoá ngoại (đọc field khác ngoài transaction sẽ lỗi lazy-load). Principal OAuth2 khác (session cũ, `oauth2Login()` trong test) → dự phòng map `Provider.fromRegistrationId` + `getName()` → `UserRepository.findBySocialAccount`; kiểu xác thực khác / provider lạ / chưa có bản ghi → 401. `config/StaleSessionFilter` (trước `AnonymousAuthenticationFilter`, bỏ qua static resource) gọi `getCurrentUser()` cho mọi request đã đăng nhập OAuth2: không xác định được user → logout (huỷ session, xoá `SecurityContext`) rồi xử lý như ẩn danh (web → `/login`, REST → 401 `ProblemDetail`); lỗi DB không bị nuốt (fail closed). REST bị kiểm tra 2 lần (filter + Service) — chấp nhận để Service tự fail closed ngoài filter chain. 401 không gửi `WWW-Authenticate` ở cả entry point lẫn `ApiExceptionHandler` (challenge `Basic` làm trình duyệt bật hộp thoại đăng nhập không dùng được).

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
- `GenericExcelImporter` (`read(InputStream, Class<T>, maxRows, maxUncompressedBytes)`) đọc header → map cột vào field theo tên (không phân biệt hoa thường) bằng Reflection, convert kiểu (String, Long, Integer, LocalDate, LocalDateTime, Enum). Đọc sheet **streaming** (SAX qua `XSSFReader` + `XMLHelper.newXMLReader()`), dừng ngay khi vượt `maxRows`; tổng dung lượng giải nén bị chặn trước khi mở file (`app.import.max-uncompressed-size`). Không dùng `XSSFWorkbook` (DOM) để đọc file upload. Importer không giữ trạng thái (không để `DataFormatter` hay object không thread-safe làm field). Metadata field cache theo class trong `excel/ExcelColumnMetadata` (`ConcurrentHashMap<Class<?>, List<FieldMeta>>`, dùng chung cho exporter) để không reflect lại mỗi dòng.
- DTO import là class mutable có constructor rỗng (`@Getter @Setter @NoArgsConstructor`, vd `PostImportRow`) vì importer gán field bằng Reflection. DTO import **không** đặt Bean Validation: Service validate DTO request tương ứng (`PostImportRow.toPostRequest()` → `PostRequest`) để import và REST API dùng chung 1 bộ quy tắc; tên field DTO import phải trùng tên component của request.
- Importer chỉ chuyển kiểu: ô công thức (FORMULA) bị từ chối, ô số ≥ 10^15 map vào field String bị từ chối (Excel đã làm tròn — phải nhập dạng text), lỗi ghi `ExcelRowError(row, column, message)` — thông điệp không echo giá trị ô. Dòng lỗi chuyển kiểu vẫn được trả về (`ExcelRow.errors()`) để Service validate tiếp các cột còn lại, báo đủ lỗi của dòng trong 1 lần. Lỗi cả file (không mở được, vượt dung lượng giải nén, thiếu cột, quá `app.import.max-rows`) ném `ExcelFileException` → Service đổi thành `BadRequestException`.
- Import bài viết upsert theo `(user, provider, externalId)` — so khớp không phân biệt hoa thường như collation MySQL; tra bài đã có theo lô (`findByUserIdAndProviderAndExternalIdIn`), không query từng dòng. Parse + validate chạy ngoài transaction; chỉ bước ghi (`PostUpsertService`) là `@Transactional`. Ô Content / Published At trống giữ nguyên giá trị cũ của bài đã có.
- `spring.servlet.multipart.resolve-lazily=true` để lỗi quá dung lượng phát sinh trong controller → `ApiExceptionHandler` trả 413 ProblemDetail.
- `GenericExcelExporter` (`write(List<T>, Class<T>, sheetName, OutputStream)`) sinh header + dòng từ field có `@ExcelColumn` (theo `order`); dùng `SXSSFWorkbook` trong try-with-resources (close lỗi không che exception gốc) và `dispose()` trong `finally` (file tạm luôn bị xoá). DTO export có thể là record (annotation trên component); kiểu hỗ trợ: String, Long/Integer/Short/Byte/Double/Float, Boolean, LocalDate, LocalDateTime, Enum — **không** BigDecimal/BigInteger (ô số Excel là double); Long vượt ±2^53 ghi dạng text để không mất chữ số. Hằng số content-type dùng chung: `GenericExcelExporter.XLSX_CONTENT_TYPE`.
- Báo cáo export lấy dữ liệu bằng projection (`PostRepository.findReportRows` → `PostReportRow`, `SELECT new` + `LEFT JOIN` snapshot mới nhất), không load Entity. "Snapshot mới nhất" dùng subquery `ORDER BY collectedAt DESC, id DESC LIMIT 1` theo từng bài (đi trên index `(post_id, collected_at)`, O(1)/bài) — **không** dùng `NOT EXISTS` + điều kiện `OR` (MySQL không range-scan được, O(n²) theo số snapshot). Giới hạn `app.export.max-rows` (query lấy `max + 1` dòng qua `Limit`, vượt → 400); query chạy trong `PostService.findReportRows` (`@Transactional(readOnly = true)`), `PostReportService` cố ý không có transaction để không giữ connection khi dựng file. Cột của DTO export trùng tên cột DTO import để file export import lại được (round-trip); `PostRequest` cắt `publishedAt` về giây vì ô ngày giờ Excel chỉ giữ tới mili giây. Ô ngày giờ Excel không có múi giờ → quy ước là **giờ theo múi giờ server** (`clock.getZone()`): import đổi sang `Instant` qua `PostImportRow.toPostRequest(zone)`, export đổi giờ UTC của DB qua `PostReportRow.inZone(zone)` trước khi ghi file.
- Export trả `StreamingResponseBody`: Service kiểm tra quyền/tần suất/giới hạn dòng và đọc dữ liệu trước, trả `ExportFile(filename, content)`; file ghi thẳng vào response, không gom vào `byte[]`. Tần suất export mỗi user giới hạn bởi `UserRateLimiter` (`app.export.min-interval`, vượt → 429 + `Retry-After`): Bucket4j lưu trạng thái bucket ở MySQL (bảng `rate_limit_buckets`, Flyway `V1_1`, bean `ProxyManager` ở `config/RateLimitConfig`, `SELECT ... FOR UPDATE`) → đúng khi chạy nhiều instance và sau restart; khoá `action:userId:intervalMs` (đổi `min-interval` thì dùng bucket mới); lỗi DB → fail closed (500). Bucket4j lấy giờ từ bean `Clock` — các instance phải đồng bộ giờ (NTP). Download chạy async: `spring.mvc.async.request-timeout` (60s) tính cả lúc dựng file lẫn lúc client tải về — chọn theo dữ liệu thực tế (10.000 dòng ~0,5 MB), không phủ trường hợp xấu nhất (~45 MB); cần phủ thì giảm `app.export.max-rows`, không kéo timeout lên vài phút (số đo ghi trong `application.yml`). Thời gian lấy từ bean `Clock` (`config/ClockConfig`), không gọi `now()` trực tiếp.
- Import: giới hạn kích thước file (`spring.servlet.multipart.max-file-size`), chỉ nhận `.xlsx` (đuôi file + magic bytes qua POI `FileMagic`), validate từng dòng và trả về danh sách lỗi theo số dòng thay vì fail cả file khi lỗi lẻ.
- Export: escape giá trị bắt đầu bằng `=`, `+`, `-`, `@` (và tab, CR) để chống CSV/Formula Injection — với .xlsx dùng style `quotePrefixed` (tương đương gõ `'` trong Excel) thay vì chèn `'` vào giá trị, để giá trị ô giữ nguyên khi import lại. Ô luôn ghi dạng giá trị, không bao giờ `setCellFormula`.
- API: `POST /api/import-posts` (multipart), `GET /api/export-report` (trả `Content-Disposition: attachment`).

### 6.3. Background Job & Multithreading
> **Đã có (Task 4.1):** `config/AsyncConfig` (`@EnableAsync` + `@EnableScheduling`; `AsyncConfigurer` là bean riêng — class config **không** implements nó vì Boot bọc bean `AsyncConfigurer` bằng proxy, làm hỏng các `@Bean` cùng class) khai báo bean `socialCrawlerExecutor` (hằng `AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME` — đặt ở `config.properties` để service không import `config`) từ `app.async.social-crawler.*` (`AppProperties.SocialCrawler`: core/max pool, queue capacity, await-termination; core ≤ max): prefix thread `social-crawler-`, `config/WaitForQueueSpacePolicy` (pool + queue đầy → thread gửi chờ tới khi queue có chỗ, không mất task, không tự chạy task; executor đã tắt → `TaskRejectedException` — **không** dùng `CallerRunsPolicy`: nó bắt thread scheduler chạy crawl làm timeout job vô hiệu, và lặng lẽ bỏ task khi executor đã tắt → future `@Async` treo), chờ task xong khi tắt app. `config/MdcTaskDecorator` chụp MDC của thread gửi sang thread pool, sau task khôi phục MDC cũ của thread (không rò `jobId`, an toàn khi task chạy trên thread gửi). Exception của `@Async void` → `config/LoggingAsyncUncaughtExceptionHandler` (log tên method + exception, không log tham số). Không override `getAsyncExecutor()`; executor riêng làm Boot bỏ `applicationTaskExecutor` nên `application.yml` đặt `spring.task.execution.mode: force` để giữ nó cho `@Async` không tên. Thread scheduler: prefix `scheduler-` (`spring.task.scheduling.*`).
>
> **Đã có (Task 4.2):** `client/social/SocialPlatformClient` (`provider()`, `fetchMetrics(accessToken, externalIds)` → `List<PostMetrics>` giá trị tuyệt đối, bài không tìm thấy bị bỏ; tối đa `MAX_IDS_PER_REQUEST` = 100 id/lần, vượt → `IllegalArgumentException`). Lỗi: `SocialPlatformException`, token rỗng/hết hạn/bị thu hồi → `InvalidAccessTokenException` (message không chứa token). Mock `FacebookMockClient`, `TwitterMockClient` kế thừa `AbstractMockSocialClient`: không lưu trạng thái, `value(h) = base + rate·h + noise(h)` với `h` = số giờ từ `EPOCH` (2026-01-01 UTC, theo bean `Clock`), `noise ∈ [0, rate)` từ `SplittableRandom` seed theo (provider, externalId, chỉ số, giờ) → tăng mỗi giờ, cùng giờ cùng số, restart không tụt. Hệ số riêng từng nền tảng ở `growth(Metric)`. `SocialPlatformClients.get(Provider)` chọn client; thiếu/trùng client cho 1 provider → app không khởi động. Thêm client thật: implementation mới của interface + bỏ `@Component` bản mock (hoặc `@Profile`).
>
> **Đã có (Task 4.3):** `scheduler/UpdateSocialMetricsJob` (`@Scheduled(cron = "${app.jobs.update-metrics.cron:0 0 * * * *}")` → `runOnce()` trả `Summary`): đặt MDC `jobId` (8 ký tự, `MDC.clear()` trong `finally`), lấy id mọi tài khoản (`SocialAccountService.findAllAccountIds`), mỗi tài khoản 1 task `service/AccountMetricsCrawler.crawl` (`@Async(AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME)` → `CompletableFuture<CrawlResult>`, bean riêng vì self-invocation), hạn chót = lúc bắt đầu + `app.jobs.update-metrics.timeout` (phủ cả lúc gửi task lẫn lúc chờ `allOf`; phải nhỏ hơn chu kỳ cron — dev 4m/cron 5 phút); future lỗi / không gửi được / chưa gửi hoặc chưa xong khi quá hạn → tính `FAILED`, không dừng job. Crawler nhận cùng hạn chót, kiểm tra trước khi bắt đầu, trước mỗi lô API và trước khi ghi → quá hạn thì bỏ kết quả, không ghi. Task của lần trước chưa kết thúc hẳn → lần chạy sau bỏ qua (log warn), không chồng. Đơn vị crawl = 1 `SocialAccount`: bài của user đó **cùng provider** (`PostService.findPostRefs` → projection `PostRef`), bài trên provider user không có tài khoản thì không được crawl. Crawler: tài khoản đã xoá hoặc token `null`/hết hạn (so UTC, `CrawlAccount.hasUsableToken`) → `SKIPPED` + log warn, không gọi API; gọi `SocialPlatformClients` theo lô `app.jobs.update-metrics.batch-size` (≤ 100) **ngoài transaction**; kết quả không tin cậy bị lọc (id không hỏi, trùng, số âm — chỉ log số lượng); `InvalidAccessTokenException`/lỗi khác → `FAILED` + log, không lưu gì. Ghi bằng `SocialMetricService.recordSnapshots` (1 transaction: `PostService.lockExistingIds` — `SELECT … FOR SHARE` lọc bài bị xoá trong lúc crawl và khoá bài còn lại tới commit, nên 1 bài bị xoá không làm rollback cả tài khoản; snapshot `collectedAt` ghi bằng `SocialMetricRepository.insertAll` — INSERT nhiều dòng, 500 dòng/câu, không `saveAll` vì id `IDENTITY` không batch được; + `SocialAccountService.markMetricsUpdated` ghi `social_accounts.last_updated_at` qua UPDATE 1 cột — cả 2 cùng 1 mốc `now(clock)` giờ UTC, không đổi `updated_at`; trả số snapshot thực sự đã lưu); tài khoản 0 bài vẫn ghi `last_updated_at`. `CrawlAccount` mang token bản rõ, `toString` che token. Profile `dev` cron mỗi 5 phút, `test` cron `"-"` (tắt — test gọi `runOnce()`). Chỉ 1 instance (job tự bỏ qua lần chạy khi task lần trước còn chạy); nhiều instance cần khoá phân tán (ShedLock) — chưa làm. Test luồng thật: `scheduler/UpdateSocialMetricsJobIntegrationTest` (Testcontainers; dự án chưa có failsafe nên test tích hợp đặt tên `*Test`).
>
> **Đã có (Task 4.4):** `/dashboard` hiển thị "Last updated time": `DashboardController` (thin) → `service/DashboardService.findLastUpdatedAt()` — owner từ `CurrentUserService`, `SocialAccountService.findLastMetricsUpdatedAt(userId)` → `SocialAccountRepository.findMaxLastUpdatedAtByUserId` (`MAX(last_updated_at)` chỉ trên tài khoản của user đó), đổi UTC → `ZonedDateTime` theo múi giờ server (`clock.getZone()`). Template: `<time datetime="…+07:00">` (`#temporals.format(…, 'yyyy-MM-dd''T''HH:mm:ssXXX')` — cắt về giây vì HTML chỉ cho tối đa 3 chữ số lẻ còn DB lưu micro giây; không dùng `toOffsetDateTime()` (in 6 chữ số lẻ) hay `#temporals.formatISO` (ra `+0700`)) + `#temporals.format(…, 'yyyy-MM-dd HH:mm:ss xxx')`; chưa có → "Chưa cập nhật". Trang tĩnh — cập nhật realtime để giai đoạn 6. `exception/WebExceptionHandler` (`@ControllerAdvice` cho `controller/web`): `AuthenticationRequiredException` (session đăng nhập nhưng không map được user) → `util/StaleSessionLogout` huỷ session (dùng chung với `StaleSessionFilter` — sửa bước logout/log ở 1 chỗ) + redirect `/login?error` (không 500, không vòng lặp `/login` ↔ `/dashboard`). `@WebMvcTest(DashboardController)` cần `@MockitoBean DashboardService`.

- `@EnableScheduling`, `@EnableAsync` đặt trong class config riêng, không đặt ở Application class.
- Khai báo bean `ThreadPoolTaskExecutor` có tên (vd `socialCrawlerExecutor`): core/max pool, queue capacity, `threadNamePrefix`, `RejectedExecutionHandler`, `setWaitForTasksToCompleteOnShutdown(true)`. Gọi `@Async("socialCrawlerExecutor")` — không dùng executor mặc định.
- `updateSocialMetricsJob` chạy mỗi 1 giờ: `@Scheduled(cron = "${app.jobs.update-metrics.cron:0 0 * * * *}")` — cron để trong config để dev có thể chạy nhanh hơn.
- Xử lý nhiều tài khoản song song bằng `CompletableFuture` trên executor trên; lỗi 1 tài khoản không được làm dừng cả job (catch + log từng task).
- Lưu ý bẫy `@Async`/`@Transactional`: gọi nội bộ trong cùng class (self-invocation) **không** qua proxy → tách ra bean khác.
- Exception trong `@Async void` phải được xử lý qua `AsyncUncaughtExceptionHandler`.
- Mock API: interface `SocialPlatformClient` với implementation mock trả số liệu ngẫu nhiên có xu hướng tăng; chọn implementation theo `Provider`. Giữ interface đủ sạch để sau này thay bằng client thật.
- Lưu `last_updated_at` sau mỗi lần job chạy để hiển thị "Last updated time" trên dashboard.

### 6.4. JMS & Queue (ActiveMQ Artemis)
> **Đã có (Task 5.1):** `spring.artemis.mode`: `native` ở `application.yml` (broker riêng; `broker-url` có `connectTimeoutMillis=5000&callTimeout=10000` — OWASP A10), dev `${ARTEMIS_MODE:embedded}`, test `embedded` (`persistent: false`, không cần broker ngoài). Broker embedded cần `artemis-jakarta-server`; `pom.xml` ghi đè `artemis.version` = 2.44.0 vì 2.43.0 của BOM Boot 4.0.7 không khởi động broker trên JDK 24+ (`Subject.getSubject` → `UnsupportedOperationException`) — bỏ khi BOM ≥ 2.44. `config/JmsConfig` khai báo bean `MessageConverter` = `config/AliasOnlyJsonMessageConverter` (Boot gắn vào cả `JmsTemplate` lẫn listener factory mặc định): JSON qua `TextMessage`, kiểu payload ghi bằng alias trong property `_type` theo bảng `messaging/MessageTypes.TYPE_ID_MAPPINGS` — alias lạ / tên class → `MessageConversionException` (trusted packages rỗng, không `Class.forName` theo message — A08), gửi class chưa có alias → lỗi ngay phía gửi. Thêm loại message mới = thêm alias vào `MessageTypes`. Tên queue: `messaging/QueueNames`. Listener: `spring.jms.listener.session.transacted=true` (ném exception → broker giao lại), concurrency 1–3. Test cấu hình: `config/JmsConfigTest` (`ApplicationContextRunner` + broker embedded).
>
> **Đã có (Task 5.2):** Flyway `V5__import_histories.sql` (entity `ImportHistory`, enum `ImportStatus`: `PENDING`; số dòng total/created/updated/failed có CHECK `total = created + updated + failed`; FK `users` **không** cascade — test xoá user phải xoá `import_histories` trước). `PostUpsertService.upsert(owner, requests, totalRows)` (`@Transactional`) ghi bài viết rồi gọi `ImportHistoryService.record` (`Propagation.MANDATORY` — không có transaction thì `@TransactionalEventListener` bị bỏ qua lặng lẽ) → lưu lịch sử `PENDING` + `publishEvent(service/ImportCompletedEvent)` (sự kiện trong JVM). `messaging/ImportCompletedProducer` nhận sự kiện bằng `@TransactionalEventListener(AFTER_COMMIT)` → `JmsTemplate.convertAndSend(QueueNames.IMPORT_COMPLETED, ImportCompletedMessage)` (alias `importCompleted`; payload chỉ `importId`, `userId`, `totalRows`, `successRows`, `failedRows`, `occurredAt` — đổi/xoá field làm message đang trong queue không đọc được, chỉ thêm field). Rollback → không có message. Gửi lỗi (broker chết, lỗi bất ngờ) → log error kèm `importId`, không ném ra (import đã commit, tránh trả 500), lịch sử giữ `PENDING` — chấp nhận mất message, chưa có outbox/gửi lại. Producer chạy đồng bộ trên thread request, đặt MDC `importId` bằng `MDC.putCloseable` (chỉ gỡ khoá của mình, không `clear()` MDC của request). `ImportResultResponse` có `importId`. Test luồng thật: `messaging/ImportCompletedPublishIntegrationTest` (MySQL Testcontainers + Artemis embedded, đọc queue bằng `JmsTemplate` riêng có receive timeout — `JmsTemplate` của Boot mặc định chờ vô hạn).

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
- Controller: `@WebMvcTest` + `MockMvc` + `@MockitoBean` cho Service; test có POST phải kèm `.with(csrf())` và user giả lập `oauth2Login()` (app chỉ có đăng nhập OAuth2 — không dùng `user(...)`/`@WithMockUser`, principal đó không map được sang `User`). Slice Boot 4 không tự nạp config của app → thêm `@Import({SecurityConfig.class, WebConfig.class})` + `@MockitoBean CustomOAuth2UserService` + `@MockitoBean CurrentUserService`.
- Repository: `@DataJpaTest` cho query custom (`@Query`, projection), kèm `@AutoConfigureTestDatabase(replace = NONE)` + `@Import(TestcontainersConfiguration.class)` để chạy trên MySQL Testcontainers — không bao giờ chạy test ghi dữ liệu vào DB dev.
- Excel: test importer/exporter với workbook tạo trong bộ nhớ (round-trip export → import).
- Integration test cuối: `@SpringBootTest` chạy luồng import → JMS → cập nhật thống kê → WebSocket event. Ưu tiên Testcontainers (MySQL) nếu có Docker, vì migration viết cho MySQL.
- Test bảo mật bắt buộc cho mỗi endpoint mới: chưa đăng nhập → 401/redirect login; thiếu CSRF → 403; truy cập tài nguyên của user khác → 403/404 (IDOR); input không hợp lệ → 400. Với Excel: file sai định dạng, quá dung lượng, chứa công thức (`=...`). Endpoint `/api/**` mới thêm vào ma trận `controller/api/ApiSecurityMatrixTest` (ẩn danh → 401 ProblemDetail, ghi thiếu/sai CSRF → 403, Service không được gọi); IDOR kiểm tra bằng query thật trong `service/OwnershipIsolationTest` (MySQL Testcontainers, user khác → `ResourceNotFoundException`, dữ liệu không đổi).
- Mỗi tính năng mới phải có test; chạy `./mvnw test` pass trước khi tạo PR.

## 8. Cấu Hình & Môi Trường
- Dùng `application.yml` + `application-dev.yml` / `application-test.yml`. Giá trị nhạy cảm dùng `${ENV_VAR}`.
- `application.yml` là cấu hình an toàn cho production (không show-sql, không trả stacktrace/message lỗi); `dev` chỉ tăng mức log; test dùng `@ActiveProfiles("test")`.
- Cấu hình riêng của app gom dưới prefix `app.*` và bind bằng `@ConfigurationProperties` (record), không rải `@Value`. Tất cả gom trong 1 record `config/properties/AppProperties` (để riêng package `config.properties`, không đặt cạnh bean `@Configuration` — service import record này mà không tạo vòng phụ thuộc `service` ↔ `config`; nested record theo nhóm, có Bean Validation + `@DefaultValue` để fail fast khi sai cấu hình); thêm nhóm mới thì thêm nested record + giá trị trong `application.yml` + test binding trong `AppPropertiesTest`. Việc Boot đã có property sẵn (vd `spring.data.web.pageable.*`) thì dùng property của Boot, không tạo `app.*` trùng chức năng.
- Chạy sau reverse proxy kết thúc HTTPS: đặt `FORWARD_HEADERS_STRATEGY=native` (→ `server.forward-headers-strategy`); để trống khi không có proxy tin cậy (bật thì client giả được `X-Forwarded-*`).
- `.env`, file Excel mẫu chứa dữ liệu thật, thư mục `logs/` phải nằm trong `.gitignore`.

## 9. Quy Trình Git
- Nhánh chính: `main`; nhánh phát triển: `develop`. Tạo nhánh tính năng từ `develop` (`feature/<ticket>-<mo-ta>`), PR về `develop`.
- PR theo template `.github/pull_request_template.md` (điền Redmine ticket, Database Changes, How to Test).
- Issue theo template trong `.github/ISSUE_TEMPLATE/`.

## 10. Lộ Trình & Trạng Thái
Cập nhật checkbox khi hoàn thành từng giai đoạn để AI biết phạm vi hiện tại:
- [x] **1. Khởi tạo project**: cấu trúc nền, config, logging, JPA + MySQL + Flyway, entity `User`/`Post`/`SocialMetric`, CRUD `/api/posts`, `/api/metrics`, Swagger.
- [x] **2. Unit Test + Excel**: test `PostService`, `MetricService`, controller; `/api/import-posts`, `/api/export-report`; Reflection mapping.
- [x] **3. Security + CSRF + Social Login**: Facebook & Twitter login → `/dashboard`, lưu token & user.
- [x] **4. Background Job & Multithread**: `updateSocialMetricsJob` mỗi giờ, mock API, màn hình "Last updated time".
- [ ] **5. JMS**: `IMPORT_COMPLETED` → listener cập nhật thống kê tổng hợp, retry + DLQ.
- [ ] **6. WebSocket + Chart**: broadcast khi metrics cập nhật, Chart.js realtime, `/api/chart-data`.
- [ ] **7. Spring-WS + Reflection nâng cao + Tổng kết**: SOAP mock, generic export, integration test, đóng gói & demo end-to-end.

Chỉ triển khai trong phạm vi giai đoạn đang làm; không tự thêm tính năng của giai đoạn sau khi chưa được yêu cầu.
