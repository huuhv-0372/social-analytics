-- V3.1: bảng session HTTP của Spring Session JDBC — mọi instance dùng chung session (state + PKCE của luồng OAuth2,
-- SecurityContext), restart không làm user bị đăng xuất. Đánh số 3.1 (không phải 4) để không trùng V4+ của nhánh epic sau.
-- Cấu trúc theo org/springframework/session/jdbc/schema-mysql.sql (Spring Session 4.0) — tên bảng/cột do thư viện
-- quy định (ngoại lệ của quy ước snake_case số nhiều), không sửa tay. MySQL trên Linux phân biệt hoa thường tên bảng:
-- giữ nguyên chữ hoa như query của Spring Session.
-- ATTRIBUTE_BYTES là attribute đã serialize (Java serialization) — app chỉ đọc lại qua bộ lọc class
-- (config/SessionAttributeSerialization).
CREATE TABLE SPRING_SESSION
(
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE = InnoDB
  ROW_FORMAT = DYNAMIC
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
-- Job dọn session hết hạn của Spring Session (spring.session.jdbc.cleanup-cron) lọc theo EXPIRY_TIME
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES
(
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BLOB         NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE = InnoDB
  ROW_FORMAT = DYNAMIC
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
