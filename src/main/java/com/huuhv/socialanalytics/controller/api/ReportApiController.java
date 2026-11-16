package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.dto.response.ExportFile;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.service.PostReportService;
import com.huuhv.socialanalytics.service.SocialMetricReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;

@Tag(name = "Report", description = "Xuất báo cáo Excel")
@RestController
@RequiredArgsConstructor
public class ReportApiController {

    private static final MediaType XLSX = MediaType.parseMediaType(GenericExcelExporter.XLSX_CONTENT_TYPE);

    private final PostReportService postReportService;
    private final SocialMetricReportService socialMetricReportService;

    @Operation(summary = "Xuất báo cáo bài viết + metric mới nhất ra file .xlsx",
            description = "Mỗi bài viết của user đang đăng nhập là 1 dòng, kèm snapshot metric mới nhất (trống nếu chưa có). "
                    + "5 cột đầu đúng định dạng file import nên có thể sửa rồi import lại. "
                    + "Số dòng tối đa theo app.export.max-rows (vượt → 400); "
                    + "mỗi user export cách nhau tối thiểu app.export.min-interval (gọi sớm hơn → 429 + Retry-After).")
    @ApiResponse(responseCode = "200", description = "File báo cáo",
            content = @Content(mediaType = GenericExcelExporter.XLSX_CONTENT_TYPE,
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping(path = "/api/export-report", produces = GenericExcelExporter.XLSX_CONTENT_TYPE)
    public ResponseEntity<StreamingResponseBody> exportReport() {
        // Kiểm tra + query chạy ngay (lỗi → 4xx ProblemDetail); file được ghi thẳng vào response, không buffer byte[]
        return download(postReportService.exportReport());
    }

    @Operation(summary = "Xuất snapshot metric ra file .xlsx (lọc collectedAt theo khoảng, ISO-8601 có múi giờ; tuỳ chọn)",
            description = "Mỗi snapshot của các bài viết thuộc user đang đăng nhập là 1 dòng (Provider, External ID, "
                    + "Likes, Shares, Comments, Followers, Collected At — giờ theo múi giờ server), sắp theo bài rồi "
                    + "thời gian. Cùng định dạng với POST /api/import-metrics: import lại nguyên file không tạo trùng "
                    + "— snapshot đã có báo lỗi 'snapshot already exists' theo dòng, chỉ dòng mới/đã sửa được ghi. "
                    + "from/to tính cả 2 đầu; from > to → 400. Giới hạn dòng app.export.max-rows (vượt → 400, "
                    + "thu hẹp khoảng thời gian) và tần suất app.export.min-interval (→ 429 + Retry-After).")
    @ApiResponse(responseCode = "200", description = "File snapshot metric",
            content = @Content(mediaType = GenericExcelExporter.XLSX_CONTENT_TYPE,
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping(path = "/api/export-metrics", produces = GenericExcelExporter.XLSX_CONTENT_TYPE)
    public ResponseEntity<StreamingResponseBody> exportMetrics(@RequestParam(required = false) Instant from,
                                                               @RequestParam(required = false) Instant to) {
        return download(socialMetricReportService.exportMetrics(from, to));
    }

    /** Kiểm tra + query đã chạy xong (lỗi → 4xx ProblemDetail); file ghi thẳng vào response, không buffer byte[]. */
    private static ResponseEntity<StreamingResponseBody> download(ExportFile file) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content()::writeTo);
    }
}
