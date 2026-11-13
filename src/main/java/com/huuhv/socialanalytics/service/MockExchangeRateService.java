package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.client.soap.ExchangeRateQuote;
import com.huuhv.socialanalytics.exception.UnsupportedCurrencyPairException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Nguồn tỷ giá giả cho endpoint SOAP mock: không lưu trạng thái, <b>cùng 1 giờ cùng 1 số</b> (test cố định được bằng
 * {@link Clock}), mỗi giờ dao động nhẹ.
 * <p>
 * Mỗi đồng tiền có tỷ giá gốc so với VND; mỗi giờ nhân thêm hệ số trong {@code [1 - 0.5%, 1 + 0.5%)} lấy từ
 * {@link SplittableRandom} seed theo (mã tiền, giờ) — số liệu giả, không cần {@code SecureRandom}. Tỷ giá chéo
 * {@code base/quote} = VND của base ÷ VND của quote, làm tròn 6 chữ số thập phân (giới hạn của XSD). Mốc
 * {@code asOf} là đầu giờ hiện tại (UTC).
 */
@Service
@RequiredArgsConstructor
public class MockExchangeRateService {

    /** Số chữ số thập phân của rate — khớp {@code fractionDigits} trong XSD. */
    static final int RATE_SCALE = 6;
    /** Biên độ dao động mỗi giờ: ±0,5%. */
    private static final double MAX_DRIFT = 0.005;
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final String PIVOT = "VND";
    /** Số VND đổi được 1 đơn vị tiền (mốc trước khi dao động). */
    private static final Map<String, BigDecimal> VND_PER_UNIT = Map.of(
            PIVOT, BigDecimal.ONE,
            "USD", new BigDecimal("25400"),
            "EUR", new BigDecimal("27600"),
            "GBP", new BigDecimal("32100"),
            "JPY", new BigDecimal("168"));

    private final Clock clock;

    /**
     * @throws UnsupportedCurrencyPairException khi 1 trong 2 mã không được hỗ trợ hoặc 2 mã trùng nhau
     */
    public ExchangeRateQuote quote(String baseCurrency, String quoteCurrency) {
        if (!VND_PER_UNIT.containsKey(baseCurrency) || !VND_PER_UNIT.containsKey(quoteCurrency)
                || baseCurrency.equals(quoteCurrency)) {
            throw new UnsupportedCurrencyPairException();
        }
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.HOURS);
        long hour = asOf.getEpochSecond() / 3600;
        BigDecimal rate = vndPerUnit(baseCurrency, hour)
                .divide(vndPerUnit(quoteCurrency, hour), RATE_SCALE, RoundingMode.HALF_EVEN);
        return new ExchangeRateQuote(baseCurrency, quoteCurrency, rate, asOf);
    }

    private static BigDecimal vndPerUnit(String currency, long hour) {
        BigDecimal base = VND_PER_UNIT.get(currency);
        if (PIVOT.equals(currency)) {
            return base;
        }
        // String.hashCode có công thức cố định trong đặc tả Java → ổn định giữa các lần chạy JVM / các instance
        double drift = (new SplittableRandom(currency.hashCode() ^ (hour * GOLDEN_GAMMA)).nextDouble() * 2 - 1)
                * MAX_DRIFT;
        return base.multiply(BigDecimal.valueOf(1 + drift));
    }
}
