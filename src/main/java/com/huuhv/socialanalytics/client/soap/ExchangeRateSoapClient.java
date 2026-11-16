package com.huuhv.socialanalytics.client.soap;

import com.huuhv.socialanalytics.soap.exchangerate.GetExchangeRateRequest;
import com.huuhv.socialanalytics.soap.exchangerate.GetExchangeRateResponse;
import org.springframework.oxm.XmlMappingException;
import org.springframework.ws.WebServiceException;
import org.springframework.ws.client.core.support.WebServiceGatewaySupport;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Client SOAP lấy tỷ giá ({@code GetExchangeRateRequest} → {@code GetExchangeRateResponse}, hợp đồng
 * {@code xsd/exchange-rate.xsd}). {@code WebServiceTemplate} (URL, timeout, marshaller an toàn XXE) dựng ở
 * {@code config/WebServiceClientConfig}.
 * <p>
 * Response là dữ liệu không tin cậy (OWASP A08): marshaller validate theo XSD (mã tiền tệ, rate dương, tối đa 6 chữ số
 * thập phân), client kiểm tra thêm cặp tiền khớp request, {@code asOf} có múi giờ và không ở tương lai quá
 * {@link #MAX_CLOCK_SKEW}. Mọi lỗi (kết nối, timeout, SOAP Fault, XML sai, dữ liệu sai) → {@link
 * ExchangeRateUnavailableException}; không log payload.
 */
public class ExchangeRateSoapClient extends WebServiceGatewaySupport {

    /** Độ lệch đồng hồ tối đa chấp nhận giữa dịch vụ tỷ giá và app. */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    private final Clock clock;

    public ExchangeRateSoapClient(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param baseCurrency  mã ISO 4217 (3 chữ in hoa) — do code/cấu hình truyền vào, không phải input người dùng
     * @param quoteCurrency mã ISO 4217 (3 chữ in hoa)
     * @throws IllegalArgumentException          khi mã tiền tệ sai định dạng (lỗi lập trình / cấu hình)
     * @throws ExchangeRateUnavailableException khi không lấy được tỷ giá hợp lệ
     */
    public ExchangeRateQuote getExchangeRate(String baseCurrency, String quoteCurrency) {
        requireCurrencyCode(baseCurrency);
        requireCurrencyCode(quoteCurrency);
        GetExchangeRateRequest request = new GetExchangeRateRequest();
        request.setBaseCurrency(baseCurrency);
        request.setQuoteCurrency(quoteCurrency);

        Object response;
        try {
            response = getWebServiceTemplate().marshalSendAndReceive(request);
        } catch (WebServiceException | XmlMappingException e) {
            // Lỗi kết nối / timeout / HTTP lỗi / SOAP Fault / XML không hợp lệ hoặc sai schema (kể cả có DOCTYPE)
            throw new ExchangeRateUnavailableException("Exchange rate service call failed: " + e.getClass().getSimpleName());
        }
        if (!(response instanceof GetExchangeRateResponse rateResponse)) {
            throw new ExchangeRateUnavailableException("Exchange rate service returned an unexpected payload");
        }
        return toQuote(rateResponse, baseCurrency, quoteCurrency);
    }

    private ExchangeRateQuote toQuote(GetExchangeRateResponse response, String baseCurrency, String quoteCurrency) {
        if (!baseCurrency.equals(response.getBaseCurrency()) || !quoteCurrency.equals(response.getQuoteCurrency())) {
            throw new ExchangeRateUnavailableException("Exchange rate response does not match the requested pair");
        }
        BigDecimal rate = response.getRate();
        if (rate == null || rate.signum() <= 0) {
            throw new ExchangeRateUnavailableException("Exchange rate response has an invalid rate");
        }
        Instant asOf = toInstant(response.getAsOf());
        if (asOf.isAfter(clock.instant().plus(MAX_CLOCK_SKEW))) {
            throw new ExchangeRateUnavailableException("Exchange rate response is dated in the future");
        }
        return new ExchangeRateQuote(baseCurrency, quoteCurrency, rate, asOf);
    }

    private static Instant toInstant(XMLGregorianCalendar value) {
        // xs:dateTime cho phép thiếu múi giờ → không xác định được mốc tuyệt đối
        if (value == null || value.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
            throw new ExchangeRateUnavailableException("Exchange rate response has an invalid asOf");
        }
        return value.toGregorianCalendar().toInstant();
    }

    private static void requireCurrencyCode(String value) {
        if (value == null || !CURRENCY_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("Currency code must be 3 uppercase letters");
        }
    }
}
