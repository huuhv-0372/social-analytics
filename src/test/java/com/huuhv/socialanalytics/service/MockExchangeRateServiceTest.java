package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.client.soap.ExchangeRateQuote;
import com.huuhv.socialanalytics.exception.UnsupportedCurrencyPairException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MockExchangeRateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T05:42:17Z");

    private static MockExchangeRateService at(Instant instant) {
        return new MockExchangeRateService(Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void quoteIsStableWithinTheSameHourAndDatedAtStartOfHour() {
        ExchangeRateQuote first = at(NOW).quote("USD", "VND");
        ExchangeRateQuote later = at(NOW.plusSeconds(600)).quote("USD", "VND");

        assertThat(later).isEqualTo(first);
        assertThat(first.asOf()).isEqualTo(Instant.parse("2026-11-13T05:00:00Z"));
        assertThat(first.baseCurrency()).isEqualTo("USD");
        assertThat(first.quoteCurrency()).isEqualTo("VND");
    }

    @Test
    void quoteStaysWithinHalfPercentOfNominalRateAndHasSixDecimals() {
        for (int hour = 0; hour < 48; hour++) {
            BigDecimal rate = at(NOW.plusSeconds(hour * 3600L)).quote("USD", "VND").rate();
            assertThat(rate.scale()).isEqualTo(MockExchangeRateService.RATE_SCALE);
            assertThat(rate.doubleValue()).isCloseTo(25_400, within(25_400 * 0.005));
        }
    }

    @Test
    void quoteChangesBetweenHours() {
        // Dao động theo giờ: trong 1 ngày phải có ít nhất 2 giá trị khác nhau
        long distinct = IntStream.range(0, 24)
                .mapToObj(hour -> at(NOW.plusSeconds(hour * 3600L)).quote("EUR", "VND").rate())
                .distinct()
                .count();
        assertThat(distinct).isGreaterThan(1);
    }

    @Test
    void crossRateIsConsistentWithInverse() {
        MockExchangeRateService service = at(NOW);
        BigDecimal usdJpy = service.quote("USD", "JPY").rate();
        BigDecimal jpyUsd = service.quote("JPY", "USD").rate();

        // Làm tròn 6 chữ số thập phân → tích xấp xỉ 1
        assertThat(usdJpy.multiply(jpyUsd, MathContext.DECIMAL64).doubleValue()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void rejectsUnsupportedOrIdenticalCurrencies() {
        MockExchangeRateService service = at(NOW);

        assertThatThrownBy(() -> service.quote("XYZ", "VND")).isInstanceOf(UnsupportedCurrencyPairException.class);
        assertThatThrownBy(() -> service.quote("USD", "ABC")).isInstanceOf(UnsupportedCurrencyPairException.class);
        assertThatThrownBy(() -> service.quote("USD", "USD"))
                .isInstanceOf(UnsupportedCurrencyPairException.class)
                // Thông điệp cố định, không echo mã tiền người gọi gửi
                .hasMessage("Unsupported currency pair");
    }
}
