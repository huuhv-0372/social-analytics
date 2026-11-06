# 📊 Social Analytics Dashboard

Mini project khoá **Java Advance**: ứng dụng web giúp admin tổng hợp và theo dõi lượng tương tác (likes, shares, comments, followers) từ **Facebook** và **Twitter (X)**, xây dựng bằng Spring Boot.

> 🚧 Dự án đang phát triển theo lộ trình 7 giai đoạn — xem [Roadmap](#roadmap). Các tính năng bên dưới đánh dấu ⏳ là **chưa triển khai**.

## Tính Năng

| Tính năng | Mô tả | Giai đoạn | Trạng thái |
|---|---|---|---|
| CRUD bài viết & metrics | REST API `/api/posts`, `/api/metrics` + Swagger UI | 1 | ✅ |
| Import / Export Excel | Upload danh sách bài viết (`/api/import-posts`), xuất báo cáo (`/api/export-report`) bằng Apache POI + Reflection | 2 | ⏳ |
| Social Login | Đăng nhập Facebook & Twitter/X qua OAuth2, bảo vệ CSRF | 3 | ⏳ |
| Background job | `updateSocialMetricsJob` chạy mỗi giờ, crawl metrics (mock API) đa luồng | 4 | ⏳ |
| JMS | Import xong → message `IMPORT_COMPLETED` → listener cập nhật thống kê, có retry + DLQ | 5 | ⏳ |
| Dashboard realtime | Chart.js cập nhật qua WebSocket (STOMP + SockJS), API `/api/chart-data` | 6 | ⏳ |
| SOAP WebService | Gọi WebService tỷ giá mock (Spring-WS), hiển thị trên dashboard | 7 | ⏳ |

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
- **Testing:** JUnit 5, Mockito, MockMvc, `@DataJpaTest`
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

2. **Chạy** — chọn 1 trong 2 cách:

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

   Container `app` (cách A) mặc định chạy profile `dev` (`SPRING_PROFILES_ACTIVE` trong `.env`). Không bật `dev` thì Swagger UI bị tắt.

   Flyway tự tạo bảng khi app khởi động.

3. Truy cập:
   - Ứng dụng: http://localhost:8080
   - Swagger UI (chỉ profile `dev`): http://localhost:8080/swagger-ui.html — bấm **Authorize**, nhập `API_USER_NAME` / `API_USER_PASSWORD` để gọi thử API
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

# Hoặc bằng JDK local (cần MySQL đang chạy cho SocialAnalyticsApplicationTests)
./mvnw test
./mvnw clean package -DskipTests

docker compose up -d --build app     # Build lại image và chạy app sau khi sửa code
docker compose down                  # Dừng tất cả (thêm -v để xoá cả dữ liệu MySQL/Artemis)
```

Test repository (`@DataJpaTest`) chạy trên MySQL riêng do **Testcontainers** tạo (`TestcontainersConfiguration`, image `mysql:8.4`) — cần Docker đang chạy, không đọc/ghi vào DB dev. Container được tạo 1 lần và dùng chung cho các test class cùng cấu hình.

## Project Structure

Kiến trúc 3 lớp (3-Tier), package gốc `com.huuhv.socialanalytics`:

```
src/main/java/com/huuhv/socialanalytics/
├── config/          # SecurityConfig, AsyncConfig, JmsConfig, WebSocketConfig, WebServiceConfig, OpenApiConfig
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
│   └── soap/        # Spring-WS client
├── scheduler/       # Background job (@Scheduled)
├── messaging/       # JMS producer, listener, tên queue
├── websocket/       # Publisher gửi event STOMP
├── excel/           # Import/export Excel generic bằng Reflection
├── exception/       # ApiExceptionHandler, WebExceptionHandler + exception nghiệp vụ
└── util/

src/main/resources/
├── db/migration/    # Flyway migrations
├── templates/       # Thymeleaf
├── static/          # CSS, JS
└── application*.yml # Cấu hình theo profile
```

**Quy ước quan trọng:** Constructor Injection qua `@RequiredArgsConstructor` + `private final`; Controller mỏng, logic nằm ở Service; không dùng Entity để nhận/trả dữ liệu; quan hệ JPA để `LAZY`, query dùng `LEFT JOIN FETCH` chống N+1; tuân thủ OWASP Secure Coding. Chi tiết xem [CLAUDE.md](CLAUDE.md).

## Configuration

| File | Vai trò |
|---|---|
| `application.yml` | Cấu hình chung, an toàn cho production: datasource, JPA (`ddl-auto=validate`), Flyway, Artemis, ẩn chi tiết lỗi, tắt Swagger, `app.*` |
| `application-dev.yml` | Profile `dev`: bật Swagger UI, log DEBUG cho app, SQL, Security (`-Dspring-boot.run.profiles=dev`) |
| `config/OpenApiConfig` | Thông tin tài liệu OpenAPI (title, version) + khai báo HTTP Basic cho nút Authorize của Swagger UI |
| `src/test/resources/application-test.yml` | Profile `test` chỉ dành cho test, không đóng gói vào JAR (`@ActiveProfiles("test")`): log gọn, chỉ ra console |
| `logback-spring.xml` | Log console + file theo ngày trong `logs/` (giữ 30 ngày), pattern kèm MDC `jobId` / `importId` |
| `config/AppProperties` | Bind cấu hình `app.*` (record + validation), vd `app.pagination.default-size` / `max-size`, `app.import.max-rows` |
| `.env` | Giá trị bí mật cho môi trường local — copy từ `.env.example` |
| `docker-compose.yaml` | MySQL 8.4 + ActiveMQ Artemis + app, và service `maven` (JDK 25) để chạy lệnh Maven |
| `Dockerfile` | Build multi-stage: JDK 25 build JAR → JRE 25 chạy app bằng user không phải root |

### Biến môi trường

| Biến | Bắt buộc | Mô tả |
|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | ✅ | Tài khoản MySQL (compose tạo user này) |
| `MYSQL_ROOT_PASSWORD` | ✅ (compose) | Mật khẩu root của container MySQL |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | — | Mặc định `localhost` / `33069` / `social_analytics`. `DB_PORT` là port MySQL mở ra máy host; app chạy trong Docker luôn dùng `mysql:3306` |
| `APP_PORT` | — | Port app mở ra máy host khi chạy bằng Docker (mặc định `8080`) |
| `SPRING_PROFILES_ACTIVE` | — | Profile của container `app` (mặc định `dev`). Đặt profile khác (vd `prod`) để chạy cấu hình production-safe, Swagger tắt |
| `LOG_PATH` | — | Thư mục log file (mặc định `logs`; trong Docker là `/app/logs`) |
| `ARTEMIS_USER` / `ARTEMIS_PASSWORD` | ✅ | Tài khoản broker Artemis |
| `API_USER_NAME` / `API_USER_PASSWORD` | ✅ (mật khẩu) | **Tạm thời tới giai đoạn 3:** tài khoản HTTP Basic để gọi `/api/**`. Tên mặc định `dev@social-analytics.local`; thiếu mật khẩu thì app không khởi động |
| `ARTEMIS_HOST` / `ARTEMIS_PORT` | — | Mặc định `localhost` / `61616` |
| `FACEBOOK_CLIENT_ID` / `FACEBOOK_CLIENT_SECRET` | Từ giai đoạn 3 | OAuth2 credentials từ Facebook Developers |
| `TWITTER_CLIENT_ID` / `TWITTER_CLIENT_SECRET` | Từ giai đoạn 3 | OAuth2 credentials từ X Developer Portal |

> Không commit bất kỳ secret nào. Nếu lỡ commit, **thu hồi/đổi secret ngay** — xem [SECURITY.md](.github/SECURITY.md).

## Database Migrations

Dùng **Flyway**, tự chạy khi ứng dụng khởi động. File đặt tại `src/main/resources/db/migration` với quy ước tên `V<n>__<mo_ta>.sql` (vd `V1__init_schema.sql`).

Quy ước: tên bảng số nhiều, snake_case (`users`, `posts`, `social_metrics`...); `ddl-auto=validate` nên schema chỉ thay đổi qua migration. **Không sửa migration đã commit** — muốn đổi schema thì thêm file version mới.

Schema hiện tại (`V1__init_schema.sql` + `V2__posts_unique_per_user.sql`):

| Bảng | Mô tả | Ràng buộc / index chính |
|---|---|---|
| `users` | Người dùng (email có thể NULL vì X không trả email), `role` USER/ADMIN | `uk_users_email` |
| `posts` | Bài viết FB/X, `user_id` là chủ sở hữu (phân quyền theo owner) | `uk_posts_user_provider_external_id (user_id, provider, external_id)` — nhiều user theo dõi được cùng 1 bài, FK `users` |
| `social_metrics` | Snapshot likes/shares/comments/followers theo `collected_at` — mỗi lần crawl thêm 1 dòng | `idx_social_metrics_post_collected (post_id, collected_at)`, FK `posts` `ON DELETE CASCADE` |

## REST API

Tạm thời (tới giai đoạn 3) `/api/**` xác thực bằng **HTTP Basic** (`API_USER_NAME` / `API_USER_PASSWORD`); gọi ẩn danh trả 401. Mỗi user chỉ thấy/sửa dữ liệu của mình (dữ liệu của user khác trả 404).

**CSRF:** mọi response đều đặt cookie `XSRF-TOKEN`; request POST/PUT/DELETE phải gửi lại cookie đó **và** giá trị của nó trong header `X-XSRF-TOKEN`, thiếu thì trả 403. Swagger UI tự làm việc này. Với Postman, sau 1 request GET, thêm header `X-XSRF-TOKEN: {{xsrf}}` với `xsrf` lấy từ cookie.

| Method | Endpoint | Mô tả |
|---|---|---|
| GET | `/api/posts?page=0&size=20&sort=createdAt,desc` | Danh sách bài viết (size tối đa 100; sort: `createdAt`, `updatedAt`, `publishedAt`, `provider`) |
| GET | `/api/posts/{id}` | Chi tiết bài viết |
| POST | `/api/posts` | Tạo bài viết — `url` phải là https thuộc domain của provider (`facebook.com`; `x.com`/`twitter.com`) |
| PUT | `/api/posts/{id}` | Cập nhật (thay thế toàn bộ) |
| DELETE | `/api/posts/{id}` | Xoá bài viết kèm toàn bộ snapshot metric |
| GET | `/api/metrics?postId=1&from=2026-01-01T00:00:00&to=...` | Snapshot của 1 bài viết (phân trang; sort: `collectedAt`, `likes`, `shares`, `comments`, `followers`) |
| GET | `/api/metrics/latest?postId=1` | Snapshot mới nhất |
| GET | `/api/metrics/{id}` | Chi tiết snapshot |
| POST | `/api/metrics` | Ghi nhận snapshot thủ công (snapshot bất biến — không có PUT) |
| DELETE | `/api/metrics/{id}` | Xoá snapshot |
| POST | `/api/import-posts` | Import bài viết từ file `.xlsx` (multipart, field `file`) — xem bên dưới |
| GET | `/api/import-posts/template` | Tải file `.xlsx` mẫu (header + dữ liệu giả + sheet hướng dẫn) |

Ví dụ:

```bash
# 1. Request GET bất kỳ để nhận cookie XSRF-TOKEN
curl -s -o /dev/null -c cookies.txt -u "$API_USER_NAME:$API_USER_PASSWORD" http://localhost:8080/api/posts
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $7}' cookies.txt)

# 2. Gửi lại cookie + header X-XSRF-TOKEN
curl -b cookies.txt -u "$API_USER_NAME:$API_USER_PASSWORD" -X POST http://localhost:8080/api/posts \
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
- Dòng lỗi trả về kèm số dòng, dòng hợp lệ vẫn được lưu. Bài đã có (cùng provider + External ID, không phân biệt hoa thường) được **cập nhật**.

```bash
curl -b cookies.txt -u "$API_USER_NAME:$API_USER_PASSWORD" -X POST http://localhost:8080/api/import-posts \
  -H "X-XSRF-TOKEN: $XSRF" -F "file=@posts.xlsx"
# {"totalRows":3,"created":1,"updated":1,"failed":1,"errors":[{"row":4,"column":"URL","message":"must not be blank"}]}
```

## Xử Lý Lỗi

- REST (`controller/api`): `ApiExceptionHandler` (`@RestControllerAdvice`) trả `ProblemDetail` (RFC 9457): 400 (validation, kèm `errors[{field, message}]`), 401, 404, 409, 500 (thông điệp chung).
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
