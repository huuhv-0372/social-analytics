package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.SocialMetricImportRow;
import com.huuhv.socialanalytics.excel.ExcelRowError;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelImportSupportTest {

    /** Comparator dựng 1 lần cho mỗi class DTO import (không dựng lại map thứ tự cột cho mỗi dòng lỗi). */
    @Test
    void columnOrderComparatorIsBuiltOncePerRowType() {
        assertThat(ExcelImportSupport.byColumnOrder(SocialMetricImportRow.class))
                .isSameAs(ExcelImportSupport.byColumnOrder(SocialMetricImportRow.class));
    }

    @Test
    void sortsErrorsByColumnOrderWithUnmappedErrorsLast() {
        List<ExcelRowError> errors = new ArrayList<>(List.of(
                new ExcelRowError(2, null, "other"),
                new ExcelRowError(2, "Collected At", "late"),
                new ExcelRowError(2, "Provider", "first"),
                new ExcelRowError(2, "Likes", "middle")));

        errors.sort(ExcelImportSupport.byColumnOrder(SocialMetricImportRow.class));

        assertThat(errors).extracting(ExcelRowError::message).containsExactly("first", "middle", "late", "other");
    }
}
