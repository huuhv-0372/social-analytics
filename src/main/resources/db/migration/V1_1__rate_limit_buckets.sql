-- V1.1: bảng trạng thái bucket của Bucket4j (rate limit dùng chung giữa các instance, không mất khi restart).
-- Đánh số 1.1 (không phải 2) để không trùng V2/V3 của các nhánh epic sau.
-- Cột do Bucket4j đọc/ghi (Bucket4jMySQL + SELECT ... FOR UPDATE): khoá dạng chuỗi "action:userId:intervalMs",
-- state là trạng thái bucket đã serialize — không sửa tay.
CREATE TABLE rate_limit_buckets
(
    id    VARCHAR(100) NOT NULL,
    state BLOB         NULL,
    CONSTRAINT pk_rate_limit_buckets PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
