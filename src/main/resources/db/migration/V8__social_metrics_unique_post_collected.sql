-- V8: mỗi bài tối đa 1 snapshot tại 1 thời điểm. Trước đó chống trùng chỉ là "kiểm tra rồi ghi" ở Service (import
-- metric kiểm tra trong transaction đọc riêng, POST /api/metrics không kiểm tra): 2 request song song (nhiều instance
-- sau load balancer, app.import.min-interval = 0, import + POST /api/metrics cùng lúc) cùng qua bước kiểm tra rồi cùng
-- INSERT. Unique index là lớp chặn cuối — Service đổi vi phạm thành 409.
-- collected_at được cắt về mili giây trước khi ghi ở mọi đường (SocialMetricService.toSnapshotTime: API, import Excel,
-- job crawl) — ô ngày giờ Excel chỉ giữ mili giây, nên file export import lại khớp đúng giá trị đã lưu.
-- Index unique thay index thường cùng cột (vẫn phục vụ "snapshot mới nhất", lọc khoảng thời gian và khoá ngoại
-- post_id): thêm index mới trước rồi mới xoá index cũ — MySQL không cho xoá index duy nhất đang phục vụ khoá ngoại.
-- Chưa phát hành production nên không có bước dọn dữ liệu trùng: DB dev có dòng trùng thì migration lỗi → xoá dòng
-- trùng (hoặc tạo lại volume DB) rồi khởi động lại.
ALTER TABLE social_metrics
    ADD CONSTRAINT uk_social_metrics_post_collected UNIQUE (post_id, collected_at);

ALTER TABLE social_metrics
    DROP INDEX idx_social_metrics_post_collected;
