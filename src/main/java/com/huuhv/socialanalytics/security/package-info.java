/**
 * Thành phần bảo mật dùng chung giữa {@code config} (filter chain) và {@code exception} (handler) — đặt riêng để 2
 * package đó chỉ phụ thuộc một chiều vào đây (không tạo vòng {@code exception ↔ util} / {@code exception ↔ config}).
 */
package com.huuhv.socialanalytics.security;
