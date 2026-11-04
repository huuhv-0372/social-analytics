## 📋 Description
<!-- Mô tả ngắn gọn những thay đổi trong PR này -->


## 🔗 Related Issue / Redmine Ticket
<!-- Link ticket Redmine liên quan -->
- Redmine Ticket: #
- Issue: #
- Roadmap phase: <!-- 1–7, theo CLAUDE.md mục 10 -->

---

## 🚀 Type of Change
<!-- Đánh dấu (x) vào loại thay đổi phù hợp -->

- [ ] 🐛 Bug fix (non-breaking change which fixes an issue)
- [ ] ✨ New feature (non-breaking change which adds functionality)
- [ ] 💥 Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] 🔧 Refactor (code change that neither fixes a bug nor adds a feature)
- [ ] 📝 Documentation update
- [ ] 🎨 UI/UX improvement
- [ ] ⚡ Performance improvement
- [ ] 🔒 Security fix
- [ ] 🧪 Test update
- [ ] ⚙️ Config / Build / CI

---
## 🛠️ Changes Made
<!-- Liệt kê chi tiết các thay đổi -->

- 
- 
- 

---

## 🗄️ Database Changes
<!-- Có thay đổi database không? -->

- [ ] No database changes
- [ ] Added new table(s): 
- [ ] Modified existing table(s): 
- [ ] Added Flyway migration: `V__.sql` (không sửa migration đã commit)
- [ ] Added/changed index
- [ ] Entity mapping khớp schema (`ddl-auto=validate` chạy được)

---

## ⚙️ Configuration Changes
<!-- Có thay đổi cấu hình / biến môi trường không? -->

- [ ] No config changes
- [ ] New env variable(s): <!-- vd FACEBOOK_CLIENT_ID — KHÔNG ghi giá trị -->
- [ ] New/changed `app.*` properties: 
- [ ] `docker-compose.yaml` / `Dockerfile` changed

---

## 🔌 API / Contract Changes
<!-- Có thay đổi contract mà phía khác (frontend, listener, client) phụ thuộc không? -->

- [ ] No API / contract changes
- [ ] REST endpoint added/changed: <!-- method + path -->
- [ ] JMS queue/message payload changed
- [ ] WebSocket topic/payload changed
- [ ] SOAP XSD/WSDL changed
- [ ] Swagger/OpenAPI đã cập nhật

---

## 🧪 How to Test
<!-- Hướng dẫn test thủ công cho reviewer -->

**Pre-conditions:**
1. 

**Steps to test:**
1. 
2. 
3. 

**Expected result:**
- 

---

## 📸 Screenshots / Demo
<!-- Thêm screenshot hoặc gif nếu có thay đổi UI/API response -->

| Before | After |
|--------|-------|
|        |       |

---

## ✅ Checklist
<!-- Đảm bảo tất cả các mục dưới đây đã được hoàn thành trước khi tạo PR -->

**Code Quality (xem CLAUDE.md mục 5)**
- [ ] Constructor injection (`@RequiredArgsConstructor` + `private final`), không dùng `@Autowired` trên field
- [ ] Controller mỏng, business logic nằm ở Service; không dùng Entity để nhận/trả dữ liệu (dùng DTO)
- [ ] Quan hệ JPA để `LAZY`, không phát sinh N+1 (đã kiểm tra log SQL)
- [ ] `@Async` / `@Transactional` không bị gọi nội bộ trong cùng class (self-invocation)
- [ ] No unused imports / dead code
- [ ] No hardcoded credentials or sensitive data
- [ ] Logging is appropriate (not too verbose, not missing critical logs)

**Testing**
- [ ] I have tested this change locally
- [ ] Unit tests added / updated (if applicable)
- [ ] Integration tests added / updated (if applicable)
- [ ] Có test bảo mật cho endpoint mới: 401 chưa đăng nhập / 403 thiếu CSRF / 403-404 IDOR / 400 input không hợp lệ
- [ ] All existing tests pass (`./mvnw test`)

**Documentation**
- [ ] API documentation updated (Swagger/OpenAPI) if applicable
- [ ] README updated if applicable
- [ ] Redmine ticket status updated

**Security (OWASP — xem CLAUDE.md mục 5.6)**
- [ ] Endpoint mới yêu cầu đăng nhập; CSRF vẫn bật (form dùng `th:action`, fetch/STOMP gửi token)
- [ ] Có kiểm tra quyền sở hữu tài nguyên theo `id` (chống IDOR), userId lấy từ SecurityContext
- [ ] Query dùng tham số, không nối chuỗi; template dùng `th:text`, JS dùng `textContent`
- [ ] Input validate bằng Bean Validation trên DTO; upload file kiểm tra định dạng, dung lượng
- [ ] Không log/trả về access token, secret, cookie; không commit secret
- [ ] Gọi ra ngoài (SOAP, mock API) có timeout; không dùng ObjectMessage/Java deserialization

---

## 🔄 Dependencies
<!-- PR này có phụ thuộc vào PR khác hoặc thêm thư viện mới không? -->

- [ ] No dependencies
- [ ] Depends on PR #
- [ ] Must be merged after PR #
- [ ] Added new library: <!-- tên + version, lý do -->

---

## 💬 Notes for Reviewer
<!-- Thông tin thêm cho reviewer, những điểm cần chú ý đặc biệt -->


---

## ⏱️ Estimate vs Actual
<!-- So sánh thời gian estimate và thực tế (theo Redmine ticket) -->

| Task | Estimated | Actual |
|------|-----------|--------|
|      |           |        |
