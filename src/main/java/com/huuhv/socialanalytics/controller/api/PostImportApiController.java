package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.dto.response.ImportResultResponse;
import com.huuhv.socialanalytics.service.PostImportService;
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

@Tag(name = "Import", description = "Import bài viết từ file Excel")
@RestController
@RequiredArgsConstructor
public class PostImportApiController {

    static final String XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String TEMPLATE_FILENAME = "posts-import-template.xlsx";

    private final PostImportService postImportService;

    @Operation(summary = "Import bài viết từ file .xlsx (cột: Provider, External ID, URL, Content, Published At)",
            description = "Dòng lỗi trả về kèm số dòng, dòng hợp lệ vẫn được lưu; bài đã có (cùng provider + "
                    + "externalId) được cập nhật — ô Content / Published At để trống thì giữ nguyên giá trị cũ. "
                    + "External ID dài hơn 15 chữ số phải nhập dạng text. Giới hạn dung lượng theo "
                    + "spring.servlet.multipart.max-file-size và app.import.max-uncompressed-size, "
                    + "số dòng theo app.import.max-rows. File mẫu: GET /api/import-posts/template.")
    @PostMapping(path = "/api/import-posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResultResponse importPosts(@RequestParam("file") MultipartFile file) {
        return postImportService.importPosts(file);
    }

    @Operation(summary = "Tải file .xlsx mẫu để import",
            description = "Header đúng định dạng + vài dòng dữ liệu giả + sheet hướng dẫn. Import nguyên file sẽ thành công.")
    @ApiResponse(responseCode = "200", description = "File mẫu",
            content = @Content(mediaType = XLSX_MEDIA_TYPE, schema = @Schema(type = "string", format = "binary")))
    @GetMapping(path = "/api/import-posts/template", produces = XLSX_MEDIA_TYPE)
    public ResponseEntity<Resource> downloadTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(TEMPLATE_FILENAME).build().toString())
                .body(postImportService.getTemplate());
    }
}
