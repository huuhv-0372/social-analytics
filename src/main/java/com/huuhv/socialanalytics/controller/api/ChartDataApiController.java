package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.dto.response.ChartDataResponse;
import com.huuhv.socialanalytics.service.ChartDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Chart", description = "Dữ liệu tổng hợp cho biểu đồ dashboard")
@RestController
@RequiredArgsConstructor
public class ChartDataApiController {

    static final String DEFAULT_DAYS = "7";

    private final ChartDataService chartDataService;

    @Operation(summary = "Metric tổng hợp theo provider, theo ngày của user đang đăng nhập",
            description = "Mỗi ngày (theo múi giờ server, trường zone) = tổng snapshot mới nhất của từng bài tính tới "
                    + "hết ngày đó; null = provider chưa có dữ liệu tới ngày đó. Kèm lastUpdatedAt và thống kê import "
                    + "để dashboard cập nhật cùng lúc khi nhận event WebSocket.")
    @GetMapping("/api/chart-data")
    public ChartDataResponse chartData(
            @Parameter(description = "Số ngày gần nhất, tính cả hôm nay")
            @RequestParam(defaultValue = DEFAULT_DAYS) @Min(1) @Max(ChartDataService.MAX_DAYS) int days) {
        return chartDataService.getChartData(days);
    }
}
