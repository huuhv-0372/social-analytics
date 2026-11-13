# 📊 Social Analytics Dashboard

Mini project khoá **Java Advance**: ứng dụng web giúp admin tổng hợp và theo dõi lượng tương tác (likes, shares, comments, followers) từ **Facebook** và **Twitter (X)**, xây dựng bằng Spring Boot.

> ✅ Đã hoàn thành đủ 7 giai đoạn của lộ trình — xem [Roadmap](#roadmap). Muốn xem nhanh mọi tính năng, làm theo [Kịch bản demo end-to-end](#kịch-bản-demo-end-to-end).

## Tính Năng

| Tính năng | Mô tả | Giai đoạn | Trạng thái |
|---|---|---|---|
| CRUD bài viết & metrics | REST API `/api/posts`, `/api/metrics` + Swagger UI | 1 | ✅ |
| Import / Export Excel | Upload danh sách bài viết (`/api/import-posts`), xuất báo cáo (`/api/export-report`) bằng Apache POI + Reflection | 2 | ✅ |
| Social Login | Đăng nhập Facebook & Twitter/X qua OAuth2, bảo vệ CSRF | 3 | ✅ |
| Background job | `updateSocialMetricsJob` chạy mỗi giờ, crawl metrics (mock API) đa luồng | 4 | ✅ |
| JMS | Import xong → message `IMPORT_COMPLETED` → listener cập nhật thống kê (hiển thị trên dashboard), có retry + DLQ | 5 | ✅ |
| Dashboard realtime | Chart.js cập nhật qua WebSocket (STOMP + SockJS), API `/api/chart-data` | 6 | ✅ |
| SOAP WebService | Host endpoint tỷ giá mock + gọi lại bằng client Spring-WS, hiển thị trên dashboard (cache, dữ liệu cũ khi lỗi) | 7 | ✅ |
| Excel generic nhiều model | Importer/exporter Reflection qua getter/setter, thêm import/export snapshot metric (`/api/import-metrics`, `/api/export-metrics`) | 7 | ✅ |
| Integration test + coverage | Test end-to-end qua HTTP/JMS/WebSocket thật, báo cáo JaCoCo có ngưỡng tối thiểu | 7 | ✅ |

## Tech Stack

- **Ngôn ngữ:** Java 25
- **Framework:** Spring Boot 4.x (`spring-boot-starter-parent` 4.0.7)
- **Build tool:** Maven (Maven Wrapper đi kèm)
- **Database:** MySQL 8 + Spring Data JPA / Hibernate
- **Migration:** Flyway (`src/main/resources/db/migration`)
- **Security:** Spring Security + OAuth2 Client (Facebook, Twitter/X) + CSRF
- **Messaging:** JMS với ActiveMQ Artemis
- **Realtime:** WebSocket + STOMP, client SockJS
- **Scheduler / Multithread:** `@Scheduled`, `@Async`, `ThreadPoolTaskExecutor`
- **Excel:** Apache POI 5.4
- **WebService:** Spring-WS (SOAP) + JAXB
- **View:** Thymeleaf + Thymeleaf Layout Dialect, Bootstrap 5, Chart.js, Vanilla JS
- **API Docs:** springdoc-openapi 3.x (Swagger UI)
- **Testing:** JUnit 5, Mockito, MockMvc, `@DataJpaTest`, Testcontainers (MySQL), Awaitility, Spring-WS test; coverage JaCoCo
- **Khác:** Lombok, SLF4J + Logback

## Prerequisites

- **Docker** (Docker Desktop trên Windows/Mac) — đủ để build, chạy và test toàn bộ dự án, **không cần cài JDK/Maven**.
- *(Tuỳ chọn)* **JDK 25** nếu muốn chạy app trực tiếp trên máy (pom.xml pin `<java.version>25</java.version>` — JDK 17/21 không build được). Không cần cài Maven — dùng Maven Wrapper (`mvnw.cmd` / `./mvnw`).

## Quick Start

1. **Tạo file `.env`** từ mẫu và điền mật khẩu (file này đã nằm trong `.gitignore`, **không commit**):

   ```bash
   # Windows (PowerShell)
   Copy-Item .env.example .env

   # Mac/Linux
   cp .env.example .env
   ```

   > `DB_USERNAME` phải là user thường (không dùng `root`) — container MySQL sẽ tự tạo user này.

   **Đăng nhập Facebook** (bắt buộc — thiếu `FACEBOOK_CLIENT_ID` / `FACEBOOK_CLIENT_SECRET` thì app không khởi động):
   1. Tạo app tại [Facebook Developers](https://developers.facebook.com/apps) (use case *Authenticate and request data from users with Facebook Login*).
   2. *Facebook Login → Settings → Valid OAuth Redirect URIs*: thêm `http://localhost:8080/login/oauth2/code/facebook` (phải khớp chính xác, kể cả port).
   3. *App settings → Basic*: copy **App ID** → `FACEBOOK_CLIENT_ID`, **App Secret** → `FACEBOOK_CLIENT_SECRET` trong `.env`.

   **Đăng nhập X (Twitter)** (bắt buộc — thiếu `TWITTER_CLIENT_ID` / `TWITTER_CLIENT_SECRET` thì app không khởi động):
   1. Tạo Project + App tại [X Developer Portal](https://developer.x.com/en/portal/dashboard).
   2. *User authentication settings*: App permissions **Read**, Type of App **Web App, Automated App or Bot** (confidential client), Callback URI `http://localhost:8080/login/oauth2/code/twitter`, Website URL bất kỳ.
   3. *Keys and tokens → OAuth 2.0 Client ID and Client Secret*: copy vào `TWITTER_CLIENT_ID` / `TWITTER_CLIENT_SECRET` (không phải API Key / Secret của OAuth 1.0a).

   **Key mã hoá access token** (bắt buộc — thiếu hoặc sai định dạng thì app không khởi động): access token Facebook/X được mã hoá AES-256-GCM trước khi lưu DB. Sinh key ngẫu nhiên 32 byte rồi điền vào `TOKEN_ENCRYPTION_KEY`:

   ```bash
   openssl rand -base64 32                      # Mac/Linux/Git Bash
   ```

   ```powershell
   [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))   # PowerShell 7+
   ```

   > Giữ key cố định: đổi key thì token đã lưu không giải mã được, user phải đăng nhập lại.

2. **Chạy** — chọn 1 trong 3 cách:

   **Cách A — Toàn bộ trong Docker (không cần JDK):**

   ```bash
   docker compose up -d --build     # MySQL + Artemis + app
   docker compose logs -f app       # Xem log app
   ```

   **Cách B — Hạ tầng trong Docker, app chạy bằng JDK 25 trên máy** (app tự đọc `.env` qua `spring.config.import`):

   ```bash
   docker compose up -d mysql artemis

   mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev   # Windows
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev     # Mac/Linux
   ```

   **Cách C — Đóng gói file JAR rồi chạy bằng `java -jar`** (JDK/JRE 25, hạ tầng như cách B):

   ```bash
   docker compose up -d mysql artemis

   ./mvnw clean package -DskipTests          # → target/social-analytics-0.0.1-SNAPSHOT.jar (bỏ -DskipTests để chạy test + kiểm coverage, cần Docker)
   java -jar target/social-analytics-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
   ```

   JAR là file executable của Spring Boot (đã gồm mọi dependency, template, migration Flyway, `broker.properties`, file Excel mẫu). Chạy lệnh `java -jar` **tại thư mục gốc dự án** để app đọc được `.env` (`spring.config.import: optional:file:.env`) — chạy ở thư mục khác thì truyền biến môi trường trực tiếp. Không có `--spring.profiles.active=dev` là cấu hình production: Swagger tắt, endpoint SOAP mock tắt, broker Artemis phải là `native` (`docker compose up -d artemis`), cookie session `Secure` (cần HTTPS — chạy thử trên HTTP thì đặt `SESSION_COOKIE_SECURE=false`), dashboard hiện "Không lấy được tỷ giá" nếu không đặt `SOAP_MOCK_ENDPOINT_ENABLED=true` hoặc `SOAP_EXCHANGE_RATE_URL` tới dịch vụ SOAP khác.

   Container `app` (cách A) mặc định chạy profile `dev` (`SPRING_PROFILES_ACTIVE` trong `.env`). Không bật `dev` thì Swagger UI bị tắt.

   Profile `dev` chạy broker JMS **Artemis embedded** (trong JVM, không cần container `artemis`). Cách B muốn dùng broker của compose (xem message trên web console) thì đặt `ARTEMIS_MODE=native` **và** `ARTEMIS_EMBEDDED_ENABLED=false` trong `.env` (thiếu 1 trong 2 thì app dừng khởi động và báo cần đặt biến nào); container `app` (cách A) luôn dùng broker của compose.

   > **Triển khai broker Artemis riêng (production):** retry + DLQ của queue `import-completed` cấu hình **trên broker**, không nằm trong app. Broker bắt buộc khởi động kèm file [`src/main/resources/artemis/broker.properties`](src/main/resources/artemis/broker.properties) (`artemis run --properties <đường dẫn file>`, như service `artemis` trong `docker-compose.yaml`), và tài khoản `ARTEMIS_USER` cần quyền `manage` để app tự kiểm tra cấu hình lúc khởi động. Thiếu file thì broker vẫn chạy nhưng message lỗi bị giao lại 10 lần liền rồi rơi vào queue `DLQ` chung không ai xử lý. Khi đó app log `ERROR Broker is not configured with artemis/broker.properties`. Nếu không kiểm tra được (broker chưa sẵn sàng, thiếu quyền), app log `WARN Could not verify broker retry/DLQ settings`.
   >
   > Lần import có message bị mất (broker chết đúng lúc gửi) được job `ResendImportCompletedJob` gửi lại sau khoảng 10 phút. Lần import đã vào DLQ (`status = DEAD_LETTERED`) không được tự động gửi lại: sau khi khắc phục nguyên nhân, chạy `UPDATE import_histories SET status = 'PENDING' WHERE status = 'DEAD_LETTERED'` thì job sẽ gửi lại, và mỗi lần import chỉ được cộng vào thống kê 1 lần.

   Flyway tự tạo bảng khi app khởi động.

3. Truy cập:
   - Ứng dụng: http://localhost:8080 — bấm **Login with Facebook** hoặc **Login with X**, đăng nhập xong được chuyển về `/dashboard`
   - Swagger UI (chỉ profile `dev`, phải đăng nhập): http://localhost:8080/swagger-ui.html — chưa đăng nhập sẽ bị chuyển về `/login`; đăng nhập Facebook/X xong mở lại link này, Swagger UI dùng chung session của trình duyệt
   - Artemis console: http://localhost:8161 (tài khoản `ARTEMIS_USER` / `ARTEMIS_PASSWORD`)
   - MySQL từ máy host (DBeaver, Workbench...): `localhost:33069` (theo `DB_PORT` trong `.env`)

**Các lệnh khác:**

```bash
# Chạy Maven trong container JDK 25 (không cần JDK local) — tự bật MySQL nếu chưa chạy
docker compose run --rm maven clean package -DskipTests     # Build file JAR vào target/
docker compose run --rm maven test -Dtest=PostServiceTest   # Chạy 1 class test không cần Testcontainers

# Toàn bộ test (kể cả Testcontainers) trong container: service này mount Docker socket của host,
# tức là cấp quyền tương đương root cho mọi code chạy trong build — chỉ dùng khi tin các dependency hiện tại,
# ưu tiên ./mvnw test bằng JDK local
docker compose run --rm maven-testcontainers test

# Hoặc bằng JDK local (cần Docker đang chạy cho test Testcontainers)
./mvnw test                          # Toàn bộ test + báo cáo coverage target/site/jacoco/index.html
./mvnw clean verify                  # Test + kiểm ngưỡng coverage + đóng gói JAR (chạy trước khi tạo PR)
./mvnw clean package -DskipTests

docker compose up -d --build app     # Build lại image và chạy app sau khi sửa code
docker compose down                  # Dừng tất cả (thêm -v để xoá cả dữ liệu MySQL/Artemis)
```

Integration test end-to-end `EndToEndFlowTest` chạy app trên Tomcat thật (cổng ngẫu nhiên) và đi qua toàn bộ luồng như người dùng: dashboard + tỷ giá SOAP → import Excel bài viết → JMS → thống kê → event WebSocket → job crawl metrics → import Excel metric → biểu đồ `/api/chart-data` → export 2 file Excel và đọc lại; user thứ 2 kiểm tra không nhận được event/dữ liệu của người khác. Chạy riêng: `./mvnw test -Dtest=EndToEndFlowTest` (cần Docker).

Test repository (`@DataJpaTest`) chạy trên MySQL riêng do **Testcontainers** tạo (`TestcontainersConfiguration`, image `mysql:8.4`) — cần Docker đang chạy, không đọc/ghi vào DB dev. Container được tạo 1 lần và dùng chung cho các test class cùng cấu hình.

**Coverage (JaCoCo 0.8.16):** `./mvnw test` sinh báo cáo `target/site/jacoco/index.html` (kèm `jacoco.xml` / `jacoco.csv` cho CI); `./mvnw verify` (hoặc `package` không `-DskipTests`) dừng build nếu coverage toàn project dưới ngưỡng **line 90% / branch 80%**. Không tính class JAXB sinh từ XSD và class `main`. Số đo lúc chốt (782 test, ~14 phút gồm khởi động container):

| Chỉ số | Coverage |
|---|---|
| Line | 95,1% |
| Branch | 84,0% |
| Instruction | 96,0% |
| Method | 98,5% |

Package thấp nhất (line): `exception` 87% (nhánh hiếm của `ApiExceptionHandler`: vi phạm ràng buộc DB, lấy tên tham số của lỗi validate method), `messaging` 89% (`ImportCompletedDlqListener` đọc header message khi header thiếu / sai kiểu), `excel` 89% (nhánh phòng thủ của `GenericExcelImporter`: lỗi I/O khi mở file, DTO không khởi tạo được). `service`, `repository`, `controller`, `webservice`, `websocket` ≥ 97%.

## Kịch Bản Demo End-to-End

Đi qua toàn bộ tính năng của 7 giai đoạn trong khoảng 15 phút. Kịch bản này cũng được tự động hoá trong `EndToEndFlowTest` (`./mvnw test -Dtest=EndToEndFlowTest`, cần Docker).

**Chuẩn bị:** `.env` đã điền đủ (Quick Start bước 1), chạy `docker compose up -d --build` (hoặc cách B/C với profile `dev`). Profile `dev` bật Swagger UI, endpoint SOAP mock, và chạy job crawl metrics **mỗi 5 phút** (production: mỗi giờ). Mở 2 tab: **Tab 1** http://localhost:8080/dashboard, **Tab 2** http://localhost:8080/swagger-ui.html.

| # | Thao tác | Kết quả mong đợi | Tính năng |
|---|---|---|---|
| 1 | Tab 1 khi chưa đăng nhập | Bị chuyển về `/login`. `curl -i http://localhost:8080/api/posts` → `401` `ProblemDetail` | Deny-by-default |
| 2 | Bấm **Login with Facebook** (hoặc X), đồng ý quyền | Về `/dashboard`: "Last updated time" = *Chưa cập nhật*, "Import statistics" = *Chưa có lần import nào*, badge realtime **Live**, card **Exchange rate** `1 USD = … VND` kèm giờ lấy | OAuth2 + PKCE, token mã hoá AES-GCM, WebSocket STOMP |
| 3 | Mở http://localhost:8080/services/exchangeRate.wsdl, chạy lệnh `curl` ở mục [SOAP WebService](#soap-webservice-tỷ-giá) | WSDL sinh từ XSD; response có `rate` + `asOf`. Gửi `baseCurrency` = `usd` → Fault `Validation error` | Spring-WS endpoint + client |
| 4 | Tab 2: `GET /api/import-posts/template` → *Download file*, rồi `POST /api/import-posts` chọn file vừa tải | `{"created":4,"failed":0,...}` kèm `importId` | Excel import (POI SAX + Reflection) |
| 5 | Nhìn Tab 1 (không tải lại trang) | Trong vài giây card "Import statistics" đổi thành 1 lần import / 4 dòng thành công. Log: `Import … added to import statistics of user …` | JMS `IMPORT_COMPLETED` → listener → event `IMPORT_PROCESSED` → WebSocket |
| 6 | Sửa file mẫu: dòng 2 đổi URL sang domain khác, dòng 3 ô Content gõ công thức `=1+1`, dòng 4 ô Content gõ `'=1+1` (dấu `'` → Excel lưu dạng text), import lại | Dòng 2 lỗi cột URL, dòng 3 lỗi `formulas are not allowed`, các dòng còn lại lưu (cập nhật bài cũ); thống kê tăng thêm 1 lần import | Validate theo dòng, từ chối công thức |
| 7 | Chờ tới mốc 5 phút kế tiếp | Log `Update social metrics finished … succeeded …`. Tab 1 tự làm mới: "Last updated time" đổi, biểu đồ có điểm hôm nay cho provider bạn đã đăng nhập (job chỉ crawl bài cùng provider với tài khoản) | `@Scheduled` + ShedLock, `@Async` đa luồng, mock API |
| 8 | Tab 2: `GET /api/import-metrics/template` → `POST /api/import-metrics` | `{"totalRows":8,"imported":8,"failed":0}`; Tab 1 tự cập nhật — đổi `<select>` sang 30 ngày / Followers để xem các series | Excel generic cho model thứ 2, event `METRICS_UPDATED` |
| 9 | Tab 2: `GET /api/export-report`, `GET /api/export-metrics` → mở bằng Excel; gọi lại ngay trong 10 giây | 2 file `.xlsx` (giờ theo múi giờ server; Content `=1+1` của bước 6 hiện nguyên văn, không tính thành 2); lần gọi thứ 2 → `429` + `Retry-After` | Export streaming, chống Formula Injection, rate limit Bucket4j |
| 10 | Import lại chính file `metrics-report-….xlsx` vừa export | Thành công (round-trip cùng 7 cột) — tạo thêm snapshot trùng thời điểm | Import/export đối xứng |
| 11 | Mở cửa sổ ẩn danh, đăng nhập tài khoản **khác** (vd X), làm bước 4 | Dashboard cửa sổ thường **không** đổi; `GET /api/posts/{id}` với id bài của user kia → `404` | Event theo user, chống IDOR |
| 12 | Gọi `POST /api/posts` bằng curl có cookie `SESSION` nhưng thiếu header `X-XSRF-TOKEN` | `403` | CSRF |
| 13 | Dừng app, chạy lại với `SOAP_EXCHANGE_RATE_URL=http://localhost:9/services` | Dashboard vẫn hoạt động, card tỷ giá hiện *Không lấy được tỷ giá* (đang có số trong cache mà dịch vụ lỗi thì hiện số cũ + nhãn **Dữ liệu cũ** — xem `ExchangeRateServiceTest`) | Timeout + fail-safe |
| 14 | *(Tuỳ chọn)* Artemis console http://localhost:8161 (khi dùng broker của compose) | Address `import-completed`, `DLQ.import-completed`, `dashboard-events` | Retry + DLQ trên broker |

## Project Structure

Kiến trúc 3 lớp (3-Tier), package gốc `com.huuhv.socialanalytics`:

```
src/main/java/com/huuhv/socialanalytics/
├── config/          # SecurityConfig, AsyncConfig, JmsConfig, WebSocketConfig, WebSocketSecurityConfig, WebServiceConfig, WebServiceClientConfig, OpenApiConfig, RateLimitConfig, SchedulerLockConfig, CryptoConfig ...
│   └── properties/  # AppProperties (cấu hình app.*)
├── controller/
│   ├── web/         # Controller Thymeleaf: /login, /dashboard, /posts ...
│   └── api/         # REST controller /api/**
├── dto/
│   ├── request/     # DTO nhận dữ liệu vào (không dùng Entity cho View/API)
│   └── response/    # DTO trả ra
├── entity/          # User, SocialAccount, Post, SocialMetric ...
├── enums/           # Provider, ImportStatus, Role ...
├── repository/      # Spring Data JPA repositories
├── service/         # Toàn bộ business logic (Controller chỉ gọi Service)
├── client/
│   ├── social/      # SocialPlatformClient + mock Facebook / Twitter
│   └── soap/        # Spring-WS client (ExchangeRateSoapClient)
├── webservice/      # Endpoint SOAP (@Endpoint) app tự host — mock tỷ giá
├── scheduler/       # Background job (@Scheduled)
├── messaging/       # JMS producer, listener, tên queue
├── websocket/       # Đường dẫn STOMP (StompDestinations), publisher gửi event
├── excel/           # Import/export Excel generic bằng Reflection
├── exception/       # ApiExceptionHandler, WebExceptionHandler + exception nghiệp vụ
├── security/        # Entry point / handler 401-403 (HTTP + ERROR frame STOMP), CSRF, kết quả đăng nhập OAuth2, principal, lọc deserialize session
└── util/

src/main/resources/
├── db/migration/    # Flyway migrations
├── xsd/             # Hợp đồng SOAP (XSD) — class JAXB sinh vào target/generated-sources/jaxb, không commit
├── templates/       # Thymeleaf
├── static/          # CSS, JS
└── application*.yml # Cấu hình theo profile
```

**Quy ước quan trọng:** Constructor Injection qua `@RequiredArgsConstructor` + `private final`; Controller mỏng, logic nằm ở Service; không dùng Entity để nhận/trả dữ liệu; quan hệ JPA để `LAZY`, query dùng `LEFT JOIN FETCH` chống N+1; tuân thủ OWASP Secure Coding. Chi tiết xem [CLAUDE.md](CLAUDE.md).

## Configuration

| File | Vai trò |
|---|---|
| `application.yml` | Cấu hình chung, an toàn cho production: datasource, JPA (`ddl-auto=validate`), Flyway, Artemis, ẩn chi tiết lỗi, tắt Swagger, giới hạn `size` phân trang (`spring.data.web.pageable.max-page-size`) |
| `application-dev.yml` | Profile `dev`: bật Swagger UI, cookie session không `Secure` (HTTP localhost), Artemis embedded (`ARTEMIS_MODE`), log DEBUG cho app, SQL, Security (`-Dspring-boot.run.profiles=dev`) |
| `config/OpenApiConfig` | Thông tin tài liệu OpenAPI (title, version); không khai security scheme — Swagger UI dùng session cookie sau khi đăng nhập OAuth2 |
| `src/test/resources/application-test.yml` | Profile `test` chỉ dành cho test, không đóng gói vào JAR (`@ActiveProfiles("test")`): log gọn, chỉ ra console |
| `logback-spring.xml` | Log console + file theo ngày trong `logs/` (giữ 30 ngày), pattern kèm MDC `jobId` / `importId` |
| `config/properties/AppProperties` | Bind cấu hình `app.*` (record + validation): `app.import.max-rows` / `max-uncompressed-size`, `app.export.max-rows` / `min-interval`, `app.token-encryption.key`, `app.async.social-crawler.*`, `app.jobs.*` (cron, timeout, khoá ShedLock), `app.oauth2.*` (timeout), `app.soap.exchange-rate.*` (URL, timeout, cặp tiền, cache) |
| `.env` | Giá trị bí mật cho môi trường local — copy từ `.env.example` |
| `docker-compose.yaml` | MySQL 8.4 + ActiveMQ Artemis + app, và service `maven` (JDK 25) để chạy lệnh Maven |
| `src/main/resources/artemis/broker.properties` | Retry + DLQ của queue `import-completed` (6 lần giao, 2s ×2, tối đa 30s ≈ 1 phút → `DLQ.import-completed`; DLQ giao lại vô hạn khi lỗi DB, 5s ×2 tối đa 5 phút) và address multicast `dashboard-events` (event realtime cho dashboard) — nguồn duy nhất: broker Artemis của compose mount file này, broker embedded (dev/test) nạp từ classpath. Test ghi đè delay bằng `src/test/resources/artemis/broker-test.properties` |
| `Dockerfile` | Build multi-stage: JDK 25 build JAR → JRE 25 chạy app bằng user không phải root |

### Biến môi trường

| Biến | Bắt buộc | Mô tả |
|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | ✅ | Tài khoản MySQL (compose tạo user này) |
| `MYSQL_ROOT_PASSWORD` | ✅ (compose) | Mật khẩu root của container MySQL |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | — | Mặc định `localhost` / `33069` / `social_analytics`. `DB_PORT` là port MySQL mở ra máy host; app chạy trong Docker luôn dùng `mysql:3306` |
| `APP_PORT` | — | Port app mở ra máy host khi chạy bằng Docker (mặc định `8080`) |
| `SPRING_PROFILES_ACTIVE` | — | Profile của container `app` (mặc định `dev`). Đặt profile khác (vd `prod`) để chạy cấu hình production-safe, Swagger tắt |
| `APP_TZ` | — | Múi giờ server của container `app` (compose truyền vào `TZ`, mặc định `Asia/Ho_Chi_Minh`; image không đặt thì chạy UTC). Quyết định ranh giới ngày của biểu đồ, giờ trong file Excel import/export, giờ hiển thị và log — DB luôn lưu UTC. Chạy image không qua compose thì đặt `-e TZ=Asia/Ho_Chi_Minh`; app chạy bằng JDK local dùng múi giờ của máy. Tên sai thì JVM lặng lẽ dùng `GMT` — kiểm tra dòng log `Server time zone` lúc khởi động |
| `LOG_PATH` | — | Thư mục log file (mặc định `logs`; trong Docker là `/app/logs`) |
| `FORWARD_HEADERS_STRATEGY` | — | Đặt `native` khi app chạy sau reverse proxy / load balancer tin cậy (kết thúc HTTPS) để link `Location` và cờ `Secure` của cookie theo `X-Forwarded-*`. Để trống khi truy cập trực tiếp — bật khi không có proxy thì client giả được scheme/host |
| `ARTEMIS_USER` / `ARTEMIS_PASSWORD` | ✅ | Tài khoản broker Artemis |
| `TOKEN_ENCRYPTION_KEY` | ✅ | Key AES-256 (Base64 của 32 byte) mã hoá access token Facebook/X lưu DB — xem Quick Start. Thiếu/sai thì app không khởi động; đổi key thì user phải đăng nhập lại. Test dùng key giả |
| `SESSION_COOKIE_SECURE` | — | Cookie session chỉ gửi qua HTTPS. Mặc định `true` (production); profile `dev`/`test` mặc định `false` vì chạy HTTP localhost |
| `ARTEMIS_HOST` / `ARTEMIS_PORT` | — | Mặc định `localhost` / `61616` |
| `ARTEMIS_MODE` | — | Chỉ profile `dev`: `embedded` (mặc định, broker trong JVM) hoặc `native` (broker của compose). Container `app` luôn `native`; production luôn `native` |
| `FACEBOOK_CLIENT_ID` / `FACEBOOK_CLIENT_SECRET` | ✅ | OAuth2 credentials (App ID / App Secret) từ Facebook Developers — xem Quick Start. Test dùng giá trị giả trong `application-test.yml` |
| `TWITTER_CLIENT_ID` / `TWITTER_CLIENT_SECRET` | ✅ | OAuth 2.0 Client ID / Secret từ X Developer Portal — xem Quick Start. Test dùng giá trị giả |
| `SOAP_MOCK_ENDPOINT_ENABLED` | — | Bật endpoint SOAP mock tỷ giá `/services/**` (public, không đăng nhập/CSRF — chỉ dữ liệu giả). Mặc định `false` (production); profile `dev` và container `app` của compose mặc định `true` |
| `SOAP_EXCHANGE_RATE_URL` | — | Địa chỉ dịch vụ SOAP tỷ giá mà dashboard gọi (chỉ `http`/`https`). Mặc định `http://localhost:8080/services` = endpoint mock của chính app |

> Không commit bất kỳ secret nào. Nếu lỡ commit, **thu hồi/đổi secret ngay** — xem [SECURITY.md](.github/SECURITY.md).

## Database Migrations

Dùng **Flyway**, tự chạy khi ứng dụng khởi động. File đặt tại `src/main/resources/db/migration` với quy ước tên `V<n>__<mo_ta>.sql` (vd `V1__init_schema.sql`).

Quy ước: tên bảng số nhiều, snake_case (`users`, `posts`, `social_metrics`...); `ddl-auto=validate` nên schema chỉ thay đổi qua migration. **Không sửa migration đã commit** — muốn đổi schema thì thêm file version mới.

Schema hiện tại (`V1` → `V7`):

| Bảng | Mô tả | Ràng buộc / index chính |
|---|---|---|
| `users` | Người dùng (email có thể NULL vì X không trả email), `role` USER/ADMIN | `uk_users_email` |
| `social_accounts` | Tài khoản Facebook/X của user, upsert mỗi lần đăng nhập: `provider_user_id`, `access_token` (**mã hoá AES-GCM**), `token_expires_at` | `uk_social_accounts_provider_user (provider, provider_user_id)`, FK `users` |
| `posts` | Bài viết FB/X, `user_id` là chủ sở hữu (phân quyền theo owner) | `uk_posts_user_provider_external_id (user_id, provider, external_id)` — nhiều user theo dõi được cùng 1 bài, FK `users` |
| `social_metrics` | Snapshot likes/shares/comments/followers theo `collected_at` — mỗi lần crawl thêm 1 dòng | `idx_social_metrics_post_collected (post_id, collected_at)`, FK `posts` `ON DELETE CASCADE` |
| `rate_limit_buckets` | Trạng thái bucket Bucket4j (giới hạn tần suất export theo user, dùng chung giữa các instance) — do Bucket4j đọc/ghi, không sửa tay | PK `id` (`action:userId:intervalMs`) |
| `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` | Session HTTP (Spring Session JDBC) — nhiều instance dùng chung, restart không đăng xuất | Tên bảng do thư viện quy định (chữ hoa) |
| `scheduler_locks` | Khoá ShedLock của job `@Scheduled` (mỗi lịch chỉ 1 instance chạy) | PK `name` |
| `import_histories` | Mỗi lần import bài viết: số dòng total/created/updated/failed, trạng thái `PENDING` → `PROCESSED` / `DEAD_LETTERED` (outbox của message `IMPORT_COMPLETED`) | FK `users`, index `(status, updated_at)` |
| `user_import_statistics` | Thống kê import cộng dồn theo user (card "Import statistics" trên dashboard) | PK `user_id`, FK `users` `ON DELETE CASCADE` |

`social_accounts.last_updated_at` (V4) là lần job crawl metrics thành công gần nhất — nguồn của "Last updated time" trên dashboard.

## REST API

Mọi đường dẫn mặc định phải đăng nhập (deny-by-default); chỉ `/login`, `/oauth2/**` và static resource là public. Gọi `/api/**` (và handshake WebSocket `/ws/**`) khi chưa đăng nhập trả 401 `ProblemDetail`; trang web chưa đăng nhập được redirect về `/login`. Chỉ đăng nhập bằng **Facebook hoặc X** (không có HTTP Basic / tài khoản mật khẩu); lần đăng nhập đầu tiên tạo user mới, các lần sau cập nhật access token. API dùng chung session cookie (`SESSION`, Spring Session lưu ở MySQL) của trình duyệt — Swagger UI tự gửi; với curl/Postman, copy cookie `SESSION` sau khi đăng nhập (DevTools → Application → Cookies). Mỗi user chỉ thấy/sửa dữ liệu của mình (dữ liệu của user khác trả 404).

Đăng xuất: `POST /logout` (kèm CSRF) → redirect `/login?logout`, session bị huỷ. `GET /logout` không đăng xuất.

**CSRF:** mọi response đều đặt cookie `XSRF-TOKEN`; request POST/PUT/DELETE phải gửi lại cookie đó **và** giá trị của nó trong header `X-XSRF-TOKEN`, thiếu thì trả 403. Swagger UI tự làm việc này. Với Postman, sau 1 request GET, thêm header `X-XSRF-TOKEN: {{xsrf}}` với `xsrf` lấy từ cookie. Trang Thymeleaf gọi `fetch` thì lấy header bằng `csrfHeaders()` (`static/js/csrf.js`, đọc `<meta name="_csrf">` trong layout).

**Thời gian:** mọi mốc thời gian (body, query, response) là ISO-8601 có múi giờ — server trả UTC (`2026-11-07T01:00:00Z`), client gửi `Z` hoặc offset (`2026-11-07T08:00:00+07:00`) và tự đổi sang giờ địa phương khi hiển thị. Giá trị thiếu múi giờ (`2026-11-07T08:00:00`) trả 400. Trên query string nên dùng `Z`; nếu dùng offset dương phải encode dấu `+` thành `%2B` (vd `from=2026-11-07T08:00:00%2B07:00`), vì `+` không encode bị hiểu là dấu cách → 400.

| Method | Endpoint | Mô tả |
|---|---|---|
| GET | `/api/posts?page=0&size=20&sort=createdAt,desc` | Danh sách bài viết (size tối đa 100; sort: `createdAt`, `updatedAt`, `publishedAt`, `provider`) |
| GET | `/api/posts/{id}` | Chi tiết bài viết |
| POST | `/api/posts` | Tạo bài viết — `url` phải là https thuộc domain của provider (`facebook.com`; `x.com`/`twitter.com`) |
| PUT | `/api/posts/{id}` | Cập nhật (thay thế toàn bộ) |
| DELETE | `/api/posts/{id}` | Xoá bài viết kèm toàn bộ snapshot metric |
| GET | `/api/metrics?postId=1&from=2026-01-01T00:00:00Z&to=...` | Snapshot của 1 bài viết (phân trang; sort: `collectedAt`, `likes`, `shares`, `comments`, `followers`) |
| GET | `/api/metrics/latest?postId=1` | Snapshot mới nhất |
| GET | `/api/metrics/{id}` | Chi tiết snapshot |
| POST | `/api/metrics` | Ghi nhận snapshot thủ công (snapshot bất biến — không có PUT) |
| DELETE | `/api/metrics/{id}` | Xoá snapshot |
| POST | `/api/import-posts` | Import bài viết từ file `.xlsx` (multipart, field `file`) — xem bên dưới |
| GET | `/api/import-posts/template` | Tải file `.xlsx` mẫu (header + dữ liệu giả + sheet hướng dẫn) |
| GET | `/api/export-report` | Tải báo cáo `.xlsx`: mỗi bài viết 1 dòng kèm snapshot metric mới nhất — xem bên dưới |
| POST | `/api/import-metrics` | Import snapshot metric từ file `.xlsx` (multipart, field `file`) — xem bên dưới |
| GET | `/api/import-metrics/template` | Tải file `.xlsx` mẫu import metric (snapshot giả của các bài `demo-*` + sheet hướng dẫn) |
| GET | `/api/export-metrics?from=...&to=...` | Tải mọi snapshot metric `.xlsx` (lọc `collectedAt` tuỳ chọn) — cùng định dạng file import metric |
| GET | `/api/chart-data?days=7` | Dữ liệu biểu đồ dashboard: metric theo provider, theo ngày (`days` 1–30, mặc định 7) — xem bên dưới |

Ví dụ:

```bash
# 0. Đăng nhập Facebook/X trên trình duyệt, copy giá trị cookie SESSION
SESSION_ID='<giá trị cookie SESSION>'

# 1. Request GET bất kỳ để nhận cookie XSRF-TOKEN
curl -s -o /dev/null -c cookies.txt -b "SESSION=$SESSION_ID" http://localhost:8080/api/posts
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $7}' cookies.txt)

# 2. Gửi lại session + cookie XSRF-TOKEN + header X-XSRF-TOKEN
curl -b "SESSION=$SESSION_ID; XSRF-TOKEN=$XSRF" -X POST http://localhost:8080/api/posts \
  -H "X-XSRF-TOKEN: $XSRF" \
  -H "Content-Type: application/json" \
  -d '{"provider":"FACEBOOK","externalId":"123","url":"https://www.facebook.com/page/posts/123","content":"Hello"}'
```

### Import bài viết từ Excel

File mẫu: tải qua Swagger UI (`GET /api/import-posts/template` → *Download file*) hoặc lấy trực tiếp [`src/main/resources/excel/posts-import-template.xlsx`](src/main/resources/excel/posts-import-template.xlsx) — import nguyên file sẽ thành công.

File `.xlsx`, sheet đầu tiên, dòng 1 là header (không phân biệt hoa thường, thứ tự cột tuỳ ý, cột thừa bị bỏ qua):

| Provider | External ID | URL | Content | Published At |
|---|---|---|---|---|
| `FACEBOOK` / `TWITTER` | chữ, số, `_`, `-` (≤ 100) | https, đúng domain của provider | tuỳ chọn (≤ 5000) | tuỳ chọn — ô kiểu ngày hoặc `yyyy-MM-dd HH:mm:ss` |

- Giới hạn: tối đa **5MB** (`spring.servlet.multipart.max-file-size`, vượt → 413) và **1000 dòng** (`app.import.max-rows`).
- Chỉ nhận `.xlsx` thật (kiểm tra cả nội dung); ô chứa công thức bị từ chối.
- Ô ngày giờ trong Excel không có múi giờ → **Published At hiểu theo múi giờ của server** (biến `TZ` của JVM/container), cùng quy ước với file export; DB lưu giờ UTC.
- Dòng lỗi trả về kèm số dòng, dòng hợp lệ vẫn được lưu. Bài đã có (cùng provider + External ID, không phân biệt hoa thường) được **cập nhật**.

```bash
curl -b "SESSION=$SESSION_ID; XSRF-TOKEN=$XSRF" -X POST http://localhost:8080/api/import-posts \
  -H "X-XSRF-TOKEN: $XSRF" -F "file=@posts.xlsx"
# {"totalRows":3,"created":1,"updated":1,"failed":1,"errors":[{"row":4,"column":"URL","message":"must not be blank"}]}
```

### Xuất báo cáo Excel

`GET /api/export-report` trả file `posts-report-yyyyMMdd-HHmmss.xlsx` (`Content-Disposition: attachment`), gồm mọi bài viết của user đang đăng nhập:

| Provider | External ID | URL | Content | Published At | Likes | Shares | Comments | Followers | Metrics Collected At |
|---|---|---|---|---|---|---|---|---|---|

- Cột metric lấy từ snapshot mới nhất của bài; bài chưa có snapshot để trống.
- 5 cột đầu đúng định dạng file import → có thể sửa file báo cáo rồi import lại (các cột metric bị bỏ qua khi import).
- Published At / Metrics Collected At ghi theo **múi giờ của server** (DB lưu UTC, đổi khi xuất file) — import lại trên cùng server giữ nguyên thời điểm.
- Tối đa **10000 dòng** (`app.export.max-rows`, vượt → 400).
- Mỗi user export cách nhau tối thiểu **10 giây** (`app.export.min-interval`, gọi sớm hơn → 429 kèm header `Retry-After`). Mốc chặn lưu ở MySQL (Bucket4j) nên vẫn đúng khi chạy nhiều instance hoặc sau restart.
- Tải file tối đa **60 giây** (`spring.mvc.async.request-timeout`, tính cả lúc dựng file) — quá hạn thì kết nối bị đóng. Đo với 10.000 dòng: dữ liệu thực tế (content ~200 ký tự) ~0,5 MB, dựng ~6s → dư nhiều kể cả mạng 1 Mbps; trường hợp xấu nhất (mọi cột chạm giới hạn, content 5000 ký tự) ~45 MB, dựng ~29s, cần mạng > ~10 Mbps — chấp nhận không phủ, nếu cần thì giảm `app.export.max-rows` thay vì tăng timeout.
- File được stream thẳng vào response (không dựng cả file trong bộ nhớ).
- Chống Formula Injection: ô bắt đầu bằng `=`, `+`, `-`, `@`, tab, CR được đánh dấu *quote prefix* (như gõ `'` trong Excel) → Excel hiển thị nguyên văn, không chạy như công thức.

```bash
curl -OJ -b "SESSION=$SESSION_ID" http://localhost:8080/api/export-report
```

### Import / export snapshot metric (Excel)

Importer/exporter Excel là **generic** (Reflection: metadata cột cache theo class, đọc/ghi qua getter/setter) — cùng 1 bộ code cho bài viết và snapshot metric.

File mẫu: `GET /api/import-metrics/template` (Swagger UI → *Download file*) hoặc [`src/main/resources/excel/metrics-import-template.xlsx`](src/main/resources/excel/metrics-import-template.xlsx) — 8 snapshot giả của 4 bài `demo-*` trong file mẫu import bài viết: **import file mẫu bài viết trước**, rồi import nguyên file này sẽ thành công (biểu đồ dashboard có dữ liệu ngày 01–06/06/2026).

File metric:

| Provider | External ID | Likes | Shares | Comments | Followers | Collected At |
|---|---|---|---|---|---|---|
| `FACEBOOK` / `TWITTER` | bài viết **của bạn** (không phân biệt hoa thường) | số nguyên ≥ 0 | ≥ 0 | ≥ 0 | ≥ 0 | không ở tương lai — ô kiểu ngày hoặc `yyyy-MM-dd HH:mm:ss`, theo múi giờ server |

- `POST /api/import-metrics`: mỗi dòng tạo 1 snapshot (snapshot là bản ghi theo thời gian, không cập nhật bản cũ). Bài không có trong các bài của bạn → lỗi `post not found`; trùng bài + Collected At trong file → lỗi ở dòng sau. Cùng giới hạn file / số dòng với import bài viết. Lưu xong → dashboard của bạn tự làm mới (event `METRICS_UPDATED`). Không tính vào "Import statistics" (thống kê đó chỉ cho import bài viết).
- `GET /api/export-metrics?from=2026-11-01T00:00:00Z&to=2026-11-13T00:00:00Z` → `metrics-report-yyyyMMdd-HHmmss.xlsx`, cùng 7 cột → sửa rồi import lại được (import lại nguyên file sẽ tạo snapshot trùng). `from`/`to` tuỳ chọn, tính cả 2 đầu; tối đa 10000 dòng (vượt → 400, thu hẹp khoảng thời gian); tần suất như export báo cáo (bucket riêng).

```bash
curl -b "SESSION=$SESSION_ID; XSRF-TOKEN=$XSRF" -X POST http://localhost:8080/api/import-metrics \
  -H "X-XSRF-TOKEN: $XSRF" -F "file=@metrics.xlsx"
# {"totalRows":3,"imported":2,"failed":1,"errors":[{"row":3,"column":"External ID","message":"post not found"}]}
curl -OJ -b "SESSION=$SESSION_ID" "http://localhost:8080/api/export-metrics?from=2026-11-01T00:00:00Z"
```

### Dữ liệu biểu đồ

`GET /api/chart-data?days=7` trả metric của user đang đăng nhập, tổng hợp theo provider và theo ngày (`days` ngoài 1–30 → 400):

```json
{
  "zone": "Asia/Ho_Chi_Minh",
  "labels": ["2026-11-06", "2026-11-07"],
  "series": [
    {"provider": "FACEBOOK", "likes": [null, 120], "shares": [null, 8], "comments": [null, 15], "followers": [null, 3400]},
    {"provider": "TWITTER", "likes": [40, 55], "shares": [2, 3], "comments": [5, 6], "followers": [900, 910]}
  ],
  "lastUpdatedAt": "2026-11-07T03:00:05Z",
  "importStatistics": {"totalImports": 3, "totalRows": 25, "successRows": 20, "failedRows": 5, "lastImportAt": "2026-11-06T18:15:00Z"}
}
```

- Ngày tính theo múi giờ server (`zone`). Mỗi bài lấy **snapshot mới nhất tính tới hết ngày đó** — snapshot là số tích luỹ nên không cộng mọi lần crawl; bài không được crawl trong ngày giữ giá trị trước đó. `likes`/`shares`/`comments` = tổng các bài; `followers` = của **snapshot mới nhất theo thời gian** trong các bài (là số người theo dõi tác giả: cộng theo bài sẽ đếm lặp, lấy lớn nhất sẽ giữ số cũ). Bài không còn được crawl (vd đã xoá trên nền tảng) vẫn giữ số cuối cùng; xoá bài trong app thì số liệu của bài bị xoá theo.
- Cả biểu đồ tính trong **1 query** (MySQL 8 `JSON_TABLE` + window function), không phải 1 query/ngày.
- `null` = provider chưa có bài nào có snapshot tới ngày đó (khác 0). Mỗi provider luôn có 1 phần tử trong `series`.
- `lastUpdatedAt` / `importStatistics` (`null` nếu chưa có) đi kèm để dashboard cập nhật mọi số liệu bằng 1 request khi nhận event WebSocket.

## WebSocket (realtime dashboard)

**Luồng event:** job crawl metrics xong (ít nhất 1 tài khoản thành công) hoặc listener `IMPORT_COMPLETED` vừa cộng 1 lần import vào thống kê → event phát lên JMS topic `dashboard-events` **sau khi transaction commit** (rollback → không có event) → mỗi instance app nhận 1 bản và chỉ đẩy xuống **các user có dữ liệu vừa đổi** (chủ tài khoản vừa crawl xong / chủ lần import) đang kết nối với nó, trên đích riêng `/user/queue/metrics`:

```json
{"type": "METRICS_UPDATED", "occurredAt": "2026-11-12T03:00:00Z"}
```

`type` hiện là `METRICS_UPDATED` hoặc `IMPORT_PROCESSED` (client làm mới với mọi `type`). User khác không nhận event — không phải gọi lại API vô ích, không biết thời điểm import của người khác. Payload không chứa số liệu — client nhận event rồi gọi lại `GET /api/chart-data` (chỉ trả dữ liệu của mình). Event chỉ là tín hiệu làm mới: gửi lỗi / instance đang tắt thì bỏ qua, không retry; client tải lại dữ liệu khi có event sau hoặc khi kết nối lại.

**Trên `/dashboard`:** biểu đồ đường Chart.js theo provider/ngày (chọn metric và 7/14/30 ngày), "Last updated time" và thống kê import tự cập nhật không cần tải lại trang; badge góc phải cho biết trạng thái kết nối (`Live` / `Reconnecting…`). Nhận event → chờ 0,5–3,5s (ngẫu nhiên, tránh mọi dashboard gọi API cùng lúc khi server khởi động lại) → gọi lại `/api/chart-data`. Mỗi lần kết nối xong (kể cả lần đầu) đều tải lại dữ liệu để không lỡ event; mất kết nối thì tự nối lại sau 5s.

**Kiểm tra thủ công** (chưa có test JS tự động): chạy profile `dev` (cron metrics 5 phút) → đăng nhập → mở `/dashboard` (badge `Live`) → import 1 file Excel ở tab khác: card thống kê đổi sau vài giây; chờ job chạy: biểu đồ + "Last updated time" đổi. DevTools → Console không có lỗi CSP; Network → WS: frame `CONNECT` có header `X-XSRF-TOKEN`.

- Endpoint STOMP `/ws` (SockJS), broker prefix `/queue`, user prefix `/user`, app prefix `/app`. Client chỉ được `SUBSCRIBE /user/queue/metrics` (Spring chỉ giao event của chính user đó); mọi `SEND` và destination khác (kể cả `SUBSCRIBE` thẳng `/queue/**`) bị từ chối bằng ERROR frame `Access denied` rồi đóng kết nối.
- Phải đăng nhập: handshake dùng session cookie `SESSION` của trình duyệt — chưa đăng nhập thì `/ws/**` trả 401 `ProblemDetail`.
- **CSRF ở frame `CONNECT`:** header `X-XSRF-TOKEN` chứa token đã mask (`<meta name="_csrf">`) **hoặc** giá trị raw của cookie `XSRF-TOKEN` — giống header HTTP; handshake phải mang cookie `XSRF-TOKEN` (trình duyệt tự gửi). Dashboard đọc cookie hiện tại trước mỗi lần kết nối (`currentCsrfHeaders()` trong `static/js/csrf.js`) nên vẫn kết nối lại được sau khi đăng nhập lại ở tab khác. Request HTTP dưới `/ws/**` (POST của transport dự phòng SockJS) không kiểm CSRF — đây là ngoại lệ duy nhất, vì kết nối không qua được `CONNECT` thì không frame nào được xử lý.
- Chạy nhiều instance sau load balancer: transport WebSocket không cần gì thêm; transport dự phòng của SockJS (xhr-streaming/polling) giữ session SockJS trong RAM nên cần **sticky session**. Transport iframe của SockJS không dùng được (`X-Frame-Options: DENY`).
- Kết nối WebSocket không gia hạn session HTTP và không tự đóng khi logout / session hết hạn (Spring Session JDBC không phát sự kiện huỷ session) — payload event không chứa số liệu, client gọi API gặp 401 thì chuyển về `/login`.

## SOAP WebService (tỷ giá)

App vừa **host** 1 endpoint SOAP mock (Spring-WS, contract-first từ `src/main/resources/xsd/exchange-rate.xsd`) vừa **gọi lại** nó bằng client `WebServiceGatewaySupport`. Class JAXB sinh tự động khi build (`./mvnw generate-sources` để sinh riêng) vào `target/generated-sources/jaxb` — không sửa tay, không commit.

- Bật bằng `SOAP_MOCK_ENDPOINT_ENABLED=true` (profile `dev`, `test`, container `app` của compose đã bật). Tắt (mặc định production) thì `/services/**` thuộc chain bảo mật chính như mọi đường dẫn khác.
- WSDL: `http://localhost:8080/services/exchangeRate.wsdl`. Endpoint public, stateless, không CSRF (chỉ trả dữ liệu giả, không có dữ liệu user) — ngoại lệ thứ 2 của deny-by-default, cạnh `/ws/**`.
- Tỷ giá giả: USD, EUR, GBP, JPY, VND; cùng 1 giờ cùng 1 số, mỗi giờ dao động ±0,5%. Cặp không hỗ trợ → SOAP Fault `Client` `Unsupported currency pair`; request sai XSD (mã tiền không phải 3 chữ in hoa...) → Fault `Client` `Validation error`; lỗi khác → Fault `Server` `Internal error`. Request có DOCTYPE / XML hỏng → 400 (chống XXE).
- Client: URL `app.soap.exchange-rate.url`, connect timeout 2s / read timeout 3s, không theo redirect, validate response theo XSD + kiểm cặp tiền và `asOf`.
- **Trên `/dashboard`:** card "Exchange rate" hiện `1 USD = … VND` (cặp tiền: `app.soap.exchange-rate.base-currency` / `quote-currency`) và thời điểm lấy theo múi giờ server. Tỷ giá được cache 10 phút (`cache-ttl`) mỗi instance; dịch vụ SOAP lỗi/timeout thì trang vẫn hiển thị bình thường — còn giá trị cũ thì hiện kèm nhãn **Dữ liệu cũ**, chưa từng lấy được thì hiện "Không lấy được tỷ giá"; sau 1 lần lỗi không gọi lại trong 1 phút (`retry-after-failure`) để trang không phải chờ timeout. Thử: chạy dev rồi đặt `SOAP_EXCHANGE_RATE_URL=http://localhost:9/services` (cổng không có dịch vụ) và khởi động lại → "Không lấy được tỷ giá".

```bash
curl -s http://localhost:8080/services -H 'Content-Type: text/xml; charset=utf-8' -d '
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:r="urn:social-analytics:exchange-rate">
  <soapenv:Body>
    <r:GetExchangeRateRequest><r:baseCurrency>USD</r:baseCurrency><r:quoteCurrency>VND</r:quoteCurrency></r:GetExchangeRateRequest>
  </soapenv:Body>
</soapenv:Envelope>'
# ...<ns2:GetExchangeRateResponse ...><ns2:baseCurrency>USD</ns2:baseCurrency><ns2:quoteCurrency>VND</ns2:quoteCurrency>
#    <ns2:rate>25387.123456</ns2:rate><ns2:asOf>2026-11-13T05:00:00Z</ns2:asOf></ns2:GetExchangeRateResponse>...
```

## Xử Lý Lỗi

- REST (`controller/api`): `ApiExceptionHandler` (`@RestControllerAdvice`) trả `ProblemDetail` (RFC 9457): 400 (validation, kèm `errors[{field, message}]`), 401, 404, 409, 429 (kèm `Retry-After`), 500 (thông điệp chung).
- Web (`controller/web`): `WebExceptionHandler` (`@ControllerAdvice`) trả view lỗi hoặc flash message.
- Không trả stack trace / thông tin nội bộ ra client.

## Bảo Mật

- Tuân thủ **OWASP Top 10 (2025)** và OWASP Secure Coding Practices — checklist chi tiết tại [CLAUDE.md mục 5.6](CLAUDE.md).
- Lỗ hổng bảo mật **không tạo issue công khai** — báo kín qua [GitHub Security Advisory](https://github.com/huuhv-0372/social-analytics/security/advisories/new). Xem [SECURITY.md](.github/SECURITY.md).

## Quy Trình Phát Triển

- Nhánh chính `main`, nhánh phát triển `develop`. Tạo nhánh tính năng từ `develop`: `feature/<issue>-<mo-ta>` (vd `feature/2-init-spring-boot-project`), PR về `develop`.
- Tạo issue theo template (Tính năng / Bug / Task kỹ thuật) và PR theo [pull request template](.github/pull_request_template.md).
- Trước khi tạo PR: `./mvnw test` phải pass.

## Roadmap

Theo dõi tiến độ tại [GitHub Project](https://github.com/users/huuhv-0372/projects/1) (7 epic, tổng estimate 38.5h / 40h).

| Giai đoạn | Nội dung | Epic |
|---|---|---|
| 1 | Khởi tạo project & cấu trúc nền | [#1](https://github.com/huuhv-0372/social-analytics/issues/1) |
| 2 | Unit Test + Import/Export Excel | [#7](https://github.com/huuhv-0372/social-analytics/issues/7) |
| 3 | Bảo mật & CSRF + Social Login | [#12](https://github.com/huuhv-0372/social-analytics/issues/12) |
| 4 | Background Job & MultiThread | [#18](https://github.com/huuhv-0372/social-analytics/issues/18) |
| 5 | JMS & Queue Handling | [#23](https://github.com/huuhv-0372/social-analytics/issues/23) |
| 6 | WebSocket + Chart Visualization | [#28](https://github.com/huuhv-0372/social-analytics/issues/28) |
| 7 | Spring WebService + Reflection nâng cao + Tổng kết | [#33](https://github.com/huuhv-0372/social-analytics/issues/33) |
