package com.huuhv.socialanalytics.client.soap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Tỷ giá: 1 đơn vị {@code baseCurrency} đổi được {@code rate} đơn vị {@code quoteCurrency}, có hiệu lực từ {@code asOf}.
 * Dùng chung cho kết quả client SOAP và dữ liệu endpoint mock trả ra (2 đầu của cùng 1 hợp đồng XSD).
 */
public record ExchangeRateQuote(String baseCurrency, String quoteCurrency, BigDecimal rate, Instant asOf) {

    public ExchangeRateQuote {
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        Objects.requireNonNull(quoteCurrency, "quoteCurrency");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(asOf, "asOf");
    }
}
