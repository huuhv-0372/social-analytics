package com.huuhv.socialanalytics.controller.web;

import com.huuhv.socialanalytics.security.CsrfCookies;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Giá trị cấu hình layout dùng ở mọi trang web. Thymeleaf 3.1 cấm truy cập class tĩnh ({@code T(...)}) trong template
 * nên hằng số được đưa vào model thay vì đọc trực tiếp.
 */
@ControllerAdvice(basePackages = "com.huuhv.socialanalytics.controller.web")
public class LayoutModelAdvice {

    /** Tên cookie chứa CSRF token raw — {@code <meta name="_csrf_cookie">} trong {@code layout/base.html}. */
    @ModelAttribute("csrfCookieName")
    public String csrfCookieName() {
        return CsrfCookies.NAME;
    }
}
