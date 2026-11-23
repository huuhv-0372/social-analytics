package com.huuhv.socialanalytics.dto.response;

import java.util.List;

/**
 * Kết quả import snapshot metric: dòng hợp lệ được lưu, dòng lỗi trả về kèm số dòng (không fail cả file).
 * Không ghi lịch sử import / thống kê import (luồng đó chỉ dành cho import bài viết).
 *
 * @param totalRows số dòng dữ liệu trong file (không tính header và dòng trống)
 * @param imported  số snapshot đã lưu
 * @param failed    số dòng không được lưu (lỗi, hoặc bài viết bị xoá trong lúc import)
 */
public record MetricImportResultResponse(int totalRows, int imported, int failed,
                                         List<ImportResultResponse.RowError> errors) {
}
