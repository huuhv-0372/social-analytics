package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.dto.response.MetricImportResultResponse;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.service.SocialMetricImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Import", description = "Import bài viết / snapshot metric từ file Excel")
@RestController
@RequiredArgsConstructor
public class SocialMetricImportApiController {

    private static final String TEMPLATE_FILENAME = "metrics-import-template.xlsx";

    private final SocialMetricImportService socialMetricImportService;

    @Operation(summary = "Import snapshot metric từ file .xlsx (cột: Provider, External ID, Likes, Shares, Comments, "
            + "Followers, Collected At)",
            description = "Mỗi dòng là 1 snapshot của 1 bài viết của user đang đăng nhập, xác định bằng Provider + "
                    + "External ID (không có / thuộc user khác → lỗi 'post not found'). Số liệu không âm, Collected At "
                    + "không ở tương lai (giờ theo múi giờ server). Dòng lỗi trả về kèm số dòng, dòng hợp lệ vẫn được "
                    + "lưu; trùng bài + Collected At trong cùng file → lỗi. File export GET /api/export-metrics đúng "
                    + "định dạng này. Giới hạn dung lượng / số dòng như POST /api/import-posts. Dashboard nhận event "
                    + "METRICS_UPDATED sau khi lưu.")
    @PostMapping(path = "/api/import-metrics", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MetricImportResultResponse importMetrics(@RequestParam("file") MultipartFile file) {
        return socialMetricImportService.importMetrics(file);
    }

    @Operation(summary = "Tải file .xlsx mẫu để import snapshot metric",
            description = "Header đúng định dạng + vài snapshot giả của các bài demo-* trong file mẫu import bài viết "
                    + "(GET /api/import-posts/template) + sheet hướng dẫn. Import file mẫu bài viết trước, sau đó import "
                    + "nguyên file này sẽ thành công.")
    @ApiResponse(responseCode = "200", description = "File mẫu",
            content = @Content(mediaType = GenericExcelExporter.XLSX_CONTENT_TYPE,
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping(path = "/api/import-metrics/template", produces = GenericExcelExporter.XLSX_CONTENT_TYPE)
    public ResponseEntity<Resource> downloadTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(TEMPLATE_FILENAME).build().toString())
                .body(socialMetricImportService.getTemplate());
    }
}
