-- V7: index cho ResendImportCompletedJob — mỗi 10 phút tìm import còn PENDING lâu (WHERE status = 'PENDING' AND
-- updated_at < ? ORDER BY updated_at, id). Không có index thì mỗi lần chạy quét toàn bảng import_histories (tăng theo
-- mỗi lần import, đa số đã PROCESSED).
CREATE INDEX idx_import_histories_status_updated ON import_histories (status, updated_at);
