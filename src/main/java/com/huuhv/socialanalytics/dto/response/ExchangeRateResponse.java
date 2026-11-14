package com.huuhv.socialanalytics.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tỷ giá hiển thị trên dashboard: 1 {@code baseCurrency} = {@code rate} {@code quoteCurrency}.
 *
 * @param asOf      mốc tỷ giá có hiệu lực do dịch vụ SOAP trả về
 * @param fetchedAt lúc app lấy được tỷ giá này từ dịch vụ SOAP ("thời điểm lấy")
 * @param stale     {@code true} = giá trị cũ còn giữ trong cache: lần gọi dịch vụ gần nhất bị lỗi, hoặc đã quá 2 ×
 *                  {@code cache-ttl} chưa được làm mới (job làm mới không chạy)
 */
public record ExchangeRateResponse(String baseCurrency, String quoteCurrency, BigDecimal rate, Instant asOf,
                                   Instant fetchedAt, boolean stale) {
}
