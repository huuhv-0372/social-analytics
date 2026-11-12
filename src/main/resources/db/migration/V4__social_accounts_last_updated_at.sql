-- V4: thời điểm job updateSocialMetricsJob cập nhật metrics thành công gần nhất cho tài khoản.
-- Lưu giờ UTC (cùng quy ước với token_expires_at) vì DB có thể dùng chung giữa app chạy ở múi giờ khác nhau;
-- dashboard đổi sang múi giờ của server khi hiển thị. NULL = chưa cập nhật lần nào.

ALTER TABLE social_accounts
    ADD COLUMN last_updated_at DATETIME(6) NULL AFTER token_expires_at;
