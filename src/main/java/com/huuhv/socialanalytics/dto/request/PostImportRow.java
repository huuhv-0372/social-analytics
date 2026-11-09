package com.huuhv.socialanalytics.dto.request;

import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.excel.ExcelColumn;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 1 dòng của file import bài viết. Class mutable + constructor rỗng vì {@code GenericExcelImporter} gán field
 * bằng Reflection; owner không có trong file — lấy từ user đang đăng nhập.
 * <p>
 * Không đặt Bean Validation ở đây: Service validate {@link #toPostRequest(ZoneId)} để import và REST API dùng chung
 * đúng 1 bộ quy tắc. Tên field phải trùng tên component của {@link PostRequest} (để map vi phạm về đúng cột).
 * <p>
 * Ô ngày giờ của Excel không có múi giờ: {@code publishedAt} là giờ theo múi giờ của server (cùng quy ước với file
 * export), đổi sang mốc tuyệt đối khi tạo {@link PostRequest}.
 */
@Getter
@Setter
@NoArgsConstructor
public class PostImportRow {

    @ExcelColumn(name = "Provider", order = 1)
    private Provider provider;

    @ExcelColumn(name = "External ID", order = 2)
    private String externalId;

    @ExcelColumn(name = "URL", order = 3)
    private String url;

    @ExcelColumn(name = "Content", order = 4)
    private String content;

    @ExcelColumn(name = "Published At", order = 5)
    private LocalDateTime publishedAt;

    /**
     * @param zone múi giờ dùng để hiểu giá trị ô ngày giờ (múi giờ của server, lấy từ bean {@code Clock})
     */
    public PostRequest toPostRequest(ZoneId zone) {
        return new PostRequest(provider, externalId, url, content,
                publishedAt == null ? null : publishedAt.atZone(zone).toInstant());
    }
}
