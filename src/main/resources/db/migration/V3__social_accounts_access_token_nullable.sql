-- V3: access_token cho phép NULL = "không có token dùng được".
-- AccessTokenConverter trả null khi không giải mã được token đã lưu (đổi TOKEN_ENCRYPTION_KEY, dữ liệu hỏng). Với cột
-- NOT NULL, mọi lần cập nhật SocialAccount đó mà không gán lại token (vd job cập nhật metrics) sẽ ghi NULL và bị MySQL
-- từ chối. Code gọi API mạng xã hội phải kiểm tra token null (coi như cần đăng nhập lại), đăng nhập lại sẽ ghi đè.

ALTER TABLE social_accounts
    MODIFY access_token TEXT NULL;
