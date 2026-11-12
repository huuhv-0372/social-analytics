-- V2: tài khoản mạng xã hội (Facebook, X) liên kết với user, tạo/cập nhật mỗi lần đăng nhập OAuth2.
-- Một user có thể có nhiều tài khoản; một tài khoản phía provider chỉ thuộc về một user.

CREATE TABLE social_accounts
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    provider         VARCHAR(20)  NOT NULL,
    -- Id của user phía provider (Facebook id / X id) — khoá tra cứu khi đăng nhập
    provider_user_id VARCHAR(100) NOT NULL,
    -- Access token đã mã hoá AES-GCM (Base64 của IV + ciphertext + tag) — không bao giờ lưu bản rõ (OWASP A04)
    access_token     TEXT         NOT NULL,
    -- Thời điểm access token hết hạn; NULL khi provider không trả expires_in
    token_expires_at DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    CONSTRAINT pk_social_accounts PRIMARY KEY (id),
    -- Upsert khi đăng nhập theo (provider, provider_user_id); chặn tạo trùng khi 2 lần đăng nhập chạy song song
    CONSTRAINT uk_social_accounts_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_social_accounts_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_social_accounts_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
