-- V4.1: khoá job @Scheduled dùng chung giữa các instance (ShedLock, config/SchedulerLockConfig) — mỗi lịch chạy chỉ 1
-- instance giữ khoá và chạy job; instance chết giữa chừng thì khoá tự hết hạn sau lock-at-most-for.
-- Đánh số 4.1 (không phải 5) để không trùng V5/V6 của nhánh epic sau.
-- Cột theo tên mặc định của ShedLock (name, lock_until, locked_at, locked_by) — do thư viện đọc/ghi, không sửa tay.
-- Thời gian là giờ UTC: ShedLock chạy ở chế độ usingDbTime() → mọi câu lệnh dùng UTC_TIMESTAMP(3) của MySQL (không
-- phụ thuộc đồng hồ / múi giờ của từng instance), khớp quy ước DATETIME lưu UTC của project.
CREATE TABLE scheduler_locks
(
    name       VARCHAR(64)  NOT NULL,
    lock_until DATETIME(3)  NOT NULL,
    locked_at  DATETIME(3)  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_scheduler_locks PRIMARY KEY (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
