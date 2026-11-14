package com.huuhv.socialanalytics.dto.request;

import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.excel.ExcelColumn;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 1 dòng của file import snapshot metric. Class mutable + constructor rỗng + setter vì {@code GenericExcelImporter} tạo
 * object và gán từng cột qua setter (Reflection). Bài viết xác định bằng (Provider, External ID) trong các bài của user
 * đang đăng nhập — file không chứa id nội bộ (không tin id do client gửi — chống IDOR).
 * <p>
 * Không đặt Bean Validation ở đây: Service validate {@link #toRequest} để import và REST API ({@code POST /api/metrics})
 * dùng chung 1 bộ quy tắc. Tên field số liệu trùng tên component của {@link SocialMetricRequest}. Cùng tên cột với file
 * export ({@code SocialMetricReportRow}) → file export import lại được.
 * <p>
 * Ô ngày giờ Excel không có múi giờ: {@code collectedAt} là giờ theo múi giờ nghiệp vụ ({@code app.time-zone}, như
 * file export).
 */
@Getter
@Setter
@NoArgsConstructor
public class SocialMetricImportRow {

    @ExcelColumn(name = "Provider", order = 1)
    private Provider provider;

    @ExcelColumn(name = "External ID", order = 2)
    private String externalId;

    @ExcelColumn(name = "Likes", order = 3)
    private Long likes;

    @ExcelColumn(name = "Shares", order = 4)
    private Long shares;

    @ExcelColumn(name = "Comments", order = 5)
    private Long comments;

    @ExcelColumn(name = "Followers", order = 6)
    private Long followers;

    @ExcelColumn(name = "Collected At", order = 7)
    private LocalDateTime collectedAt;

    /**
     * @param postId id bài viết đã tra theo (provider, externalId) trong các bài của owner; {@code null} khi không tìm
     *               thấy (Service báo lỗi riêng ở cột External ID)
     * @param zone   múi giờ dùng để hiểu giá trị ô ngày giờ (múi giờ nghiệp vụ, lấy từ bean {@code Clock}). Giờ
     *               local lặp lại khi lùi đồng hồ (DST) được hiểu theo offset <b>sớm hơn</b> ({@code atZone}); Service
     *               kiểm tra snapshot đã có với cả offset muộn hơn ({@code SocialMetricImportService})
     */
    public SocialMetricRequest toRequest(Long postId, ZoneId zone) {
        return new SocialMetricRequest(postId, likes, shares, comments, followers,
                collectedAt == null ? null : collectedAt.atZone(zone).toInstant());
    }
}
