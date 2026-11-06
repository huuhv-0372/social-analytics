package com.huuhv.socialanalytics.excel;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu field của DTO ứng với 1 cột Excel. Chỉ dùng trên DTO, không dùng trên Entity.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ExcelColumn {

    /** Tên cột ở dòng header — import so khớp không phân biệt hoa thường, bỏ khoảng trắng 2 đầu. */
    String name();

    /** Thứ tự cột khi export và khi liệt kê lỗi. */
    int order();
}
