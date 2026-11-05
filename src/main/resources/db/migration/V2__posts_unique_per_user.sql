-- V2: bài viết unique theo từng user thay vì toàn hệ thống.
-- Nhiều user được theo dõi cùng 1 bài; kiểm tra trùng không còn làm lộ bài viết của user khác (OWASP A01).
-- Gộp trong 1 câu ALTER: MySQL không rollback DDL, tách nhiều câu thì lỗi giữa chừng sẽ để lại schema dở dang.
-- Unique mới bắt đầu bằng user_id nên thay được idx_posts_user_id làm index cho FK fk_posts_user.
ALTER TABLE posts
    ADD CONSTRAINT uk_posts_user_provider_external_id UNIQUE (user_id, provider, external_id),
    DROP INDEX uk_posts_provider_external_id,
    DROP INDEX idx_posts_user_id;
