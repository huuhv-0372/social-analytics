-- V6: thống kê import tổng hợp theo user, do listener IMPORT_COMPLETED cập nhật bất đồng bộ.

-- Thời điểm listener cộng lần import vào thống kê (UTC); NULL = chưa xử lý (status PENDING)
ALTER TABLE import_histories
    ADD COLUMN processed_at DATETIME(6) NULL AFTER status;

-- 1 dòng / user, cộng dồn qua INSERT ... ON DUPLICATE KEY UPDATE (nguyên tử khi nhiều consumer chạy song song).
-- Dữ liệu dẫn xuất từ import_histories → xoá user thì xoá theo (cascade), có thể tính lại từ lịch sử nếu cần.
CREATE TABLE user_import_statistics
(
    user_id        BIGINT      NOT NULL,
    total_imports  BIGINT      NOT NULL,
    total_rows     BIGINT      NOT NULL,
    success_rows   BIGINT      NOT NULL,
    failed_rows    BIGINT      NOT NULL,
    -- Lần import mới nhất đã được cộng (UTC) — message có thể tới không theo thứ tự nên luôn lấy giá trị lớn hơn
    last_import_at DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    CONSTRAINT pk_user_import_statistics PRIMARY KEY (user_id),
    CONSTRAINT fk_user_import_statistics_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
