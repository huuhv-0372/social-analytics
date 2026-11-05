-- V1: schema khởi tạo cho users, posts, social_metrics

CREATE TABLE users
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    -- X (Twitter) không trả email nên cho phép NULL; UNIQUE trên MySQL chấp nhận nhiều NULL
    email        VARCHAR(255) NULL,
    display_name VARCHAR(100) NOT NULL,
    avatar_url   VARCHAR(500) NULL,
    role         VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE posts
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    -- Chủ sở hữu bài viết, dùng để phân quyền theo owner (chống IDOR)
    user_id      BIGINT       NOT NULL,
    provider     VARCHAR(20)  NOT NULL,
    external_id  VARCHAR(100) NOT NULL,
    url          VARCHAR(500) NOT NULL,
    content      TEXT         NULL,
    published_at DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    CONSTRAINT pk_posts PRIMARY KEY (id),
    CONSTRAINT uk_posts_provider_external_id UNIQUE (provider, external_id),
    CONSTRAINT fk_posts_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_posts_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Snapshot theo thời gian: mỗi lần crawl INSERT 1 dòng mới, không UPDATE
CREATE TABLE social_metrics
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    post_id      BIGINT      NOT NULL,
    likes        BIGINT      NOT NULL DEFAULT 0,
    shares       BIGINT      NOT NULL DEFAULT 0,
    comments     BIGINT      NOT NULL DEFAULT 0,
    followers    BIGINT      NOT NULL DEFAULT 0,
    collected_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_social_metrics PRIMARY KEY (id),
    CONSTRAINT fk_social_metrics_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    -- Index ghép phục vụ "metric mới nhất" và truy vấn xu hướng; đồng thời là index cho FK post_id
    INDEX idx_social_metrics_post_collected (post_id, collected_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
