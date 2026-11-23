-- V5: lịch sử import Excel — mỗi lần import (đã ghi DB) là 1 dòng, id dùng làm importId của message IMPORT_COMPLETED.
-- Ghi cùng transaction với bài viết được import → message chỉ được publish khi cả 2 đã commit.

CREATE TABLE import_histories
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    -- Người import (owner) — không cascade khi xoá user, giống posts
    user_id      BIGINT      NOT NULL,
    -- Số dòng dữ liệu trong file / tạo mới / cập nhật / lỗi (failed = total - created - updated)
    total_rows   INT         NOT NULL,
    created_rows INT         NOT NULL,
    updated_rows INT         NOT NULL,
    failed_rows  INT         NOT NULL,
    -- Trạng thái xử lý message IMPORT_COMPLETED (enum ImportStatus); PENDING = chưa được listener xử lý
    status       VARCHAR(20) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    CONSTRAINT pk_import_histories PRIMARY KEY (id),
    CONSTRAINT fk_import_histories_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_import_histories_rows CHECK (created_rows >= 0 AND updated_rows >= 0 AND failed_rows >= 0
        AND total_rows = created_rows + updated_rows + failed_rows),
    -- Lịch sử import theo user, mới nhất trước; đồng thời là index cho FK user_id
    INDEX idx_import_histories_user_created (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
