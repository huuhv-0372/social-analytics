# Security Policy

`social-analytics` là mini project phục vụ học tập (khoá Java Advance), không triển khai production và không chứa dữ liệu người dùng thật. Dù vậy, repo là public nên mọi lỗ hổng vẫn được xử lý theo quy trình báo cáo kín.

## Báo cáo lỗ hổng

**Không tạo issue, PR hay comment công khai** mô tả chi tiết lỗ hổng.

Báo kín qua [GitHub Security Advisory](https://github.com/huuhv-0372/social-analytics/security/advisories/new), nêu:
- Thành phần bị ảnh hưởng (OAuth2 login, import/export Excel, REST API, JMS, WebSocket, SOAP...)
- Các bước tái tạo và tác động có thể xảy ra
- Đề xuất cách khắc phục (nếu có)

Ví dụ cần báo kín: lộ access token/client secret, bypass đăng nhập hoặc phân quyền, IDOR, SQL injection, XSS, XXE, CSRF bị tắt.

## Lộ secret

Nếu phát hiện secret (OAuth client secret, khoá mã hoá, mật khẩu DB) đã bị commit:
1. **Thu hồi / đổi secret ngay** trên Facebook/X developer console hoặc hệ thống tương ứng — xoá commit không đủ, vì lịch sử Git đã public.
2. Sau đó mới báo qua Security Advisory và xoá secret khỏi code (chuyển sang biến môi trường).

## Góp ý bảo mật trong code review

Nhận xét về chất lượng bảo mật trong quá trình review PR (thiếu validate, thiếu test bảo mật, chưa đạt checklist OWASP ở `CLAUDE.md` mục 5.6) vẫn comment trực tiếp trên PR như bình thường. Chỉ những lỗ hổng **có thể khai thác trên code đã merge vào `main`/`develop`** mới cần báo kín.
