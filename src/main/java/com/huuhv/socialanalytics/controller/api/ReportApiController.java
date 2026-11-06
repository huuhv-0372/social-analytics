package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.dto.response.ExportFile;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.service.PostReportService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Report", description = "Xuất báo cáo Excel")
@RestController
@RequiredArgsConstructor
public class ReportApiController {

    private static final MediaType XLSX = MediaType.parseMediaType(GenericExcelExporter.XLSX_CONTENT_TYPE);

    private final PostReportService postReportService;

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
        ExportFile file = postReportService.exportReport();
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content()::writeTo);
    }
}
