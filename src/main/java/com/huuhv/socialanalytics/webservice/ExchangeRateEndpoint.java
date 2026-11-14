package com.huuhv.socialanalytics.webservice;

import com.huuhv.socialanalytics.client.soap.ExchangeRateQuote;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.MockExchangeRateService;
import com.huuhv.socialanalytics.soap.exchangerate.GetExchangeRateRequest;
import com.huuhv.socialanalytics.soap.exchangerate.GetExchangeRateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import javax.xml.datatype.DatatypeFactory;

/**
 * Endpoint SOAP mock tỷ giá ({@code POST /services}, payload {@code GetExchangeRateRequest}). Mỏng như controller:
 * request đã được validate theo XSD ({@code PayloadValidatingInterceptor} ở {@code config/WebServiceConfig}), chỉ map
 * sang Service rồi map kết quả về response.
 * <p>
 * Chỉ đăng ký khi bật {@code app.soap.exchange-rate.mock-endpoint.enabled} (public, không đăng nhập — xem
 * {@code SecurityConfig#soapFilterChain}). Cặp tiền không hỗ trợ → SOAP Fault {@code Client}; lỗi khác → Fault
 * {@code Server} thông điệp chung.
 */
@Endpoint
@ConditionalOnBooleanProperty(AppProperties.ExchangeRate.MOCK_ENDPOINT_ENABLED_PROPERTY)
@RequiredArgsConstructor
public class ExchangeRateEndpoint {

    public static final String NAMESPACE = "urn:social-analytics:exchange-rate";

    /**
     * Tạo 1 lần (tìm implementation qua ServiceLoader mỗi lần gọi {@code newDefaultInstance}); factory không giữ trạng
     * thái, dùng chung giữa các thread chỉ để tạo calendar mới.
     */
    private static final DatatypeFactory DATATYPE_FACTORY = DatatypeFactory.newDefaultInstance();

    private final MockExchangeRateService mockExchangeRateService;

    @PayloadRoot(namespace = NAMESPACE, localPart = "GetExchangeRateRequest")
    @ResponsePayload
    public GetExchangeRateResponse getExchangeRate(@RequestPayload GetExchangeRateRequest request) {
        ExchangeRateQuote quote = mockExchangeRateService.quote(request.getBaseCurrency(), request.getQuoteCurrency());
        GetExchangeRateResponse response = new GetExchangeRateResponse();
        response.setBaseCurrency(quote.baseCurrency());
        response.setQuoteCurrency(quote.quoteCurrency());
        response.setRate(quote.rate());
        // Instant.toString() là ISO-8601 UTC có "Z" → xs:dateTime luôn kèm múi giờ, không thêm phần lẻ ".000" thừa
        // (GregorianCalendar luôn mang mili giây) — giữ nguyên định dạng client đang nhận
        response.setAsOf(DATATYPE_FACTORY.newXMLGregorianCalendar(quote.asOf().toString()));
        return response;
    }
}
