package com.huuhv.socialanalytics.webservice;

import com.huuhv.socialanalytics.config.WebServiceConfig;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.MockExchangeRateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webservices.test.autoconfigure.server.WebServiceServerTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.ws.test.server.ResponseMatcher;
import org.springframework.xml.transform.StringSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.clientOrSenderFault;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.serverOrReceiverFault;
import static org.springframework.ws.test.server.ResponseMatchers.validPayload;
import static org.springframework.ws.test.server.ResponseMatchers.xpath;

/**
 * Endpoint + cấu hình Spring-WS thật ({@link WebServiceConfig}: validate XSD, resolver fault chung), gọi qua
 * {@link MockWebServiceClient} (không qua HTTP — XXE và bảo mật HTTP kiểm ở {@code SoapEndpointHttpTest}).
 */
@WebServiceServerTest(endpoints = ExchangeRateEndpoint.class,
        properties = AppProperties.ExchangeRate.MOCK_ENDPOINT_ENABLED_PROPERTY + "=true")
@Import({WebServiceConfig.class, ExchangeRateEndpointTest.FixedClock.class})
class ExchangeRateEndpointTest {

    private static final String NS = ExchangeRateEndpoint.NAMESPACE;
    private static final Map<String, String> NAMESPACES = Map.of("r", NS);

    @Autowired
    private MockWebServiceClient client;

    @MockitoSpyBean
    private MockExchangeRateService mockExchangeRateService;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-13T05:42:17Z"), ZoneOffset.UTC);
        }

        @Bean
        MockExchangeRateService mockExchangeRateService(Clock clock) {
            return new MockExchangeRateService(clock);
        }
    }

    private static StringSource request(String base, String quote) {
        return new StringSource("<GetExchangeRateRequest xmlns='" + NS + "'><baseCurrency>" + base
                + "</baseCurrency><quoteCurrency>" + quote + "</quoteCurrency></GetExchangeRateRequest>");
    }

    @Test
    void returnsRateValidAgainstSchema() throws Exception {
        client.sendRequest(withPayload(request("USD", "VND")))
                .andExpect(noFault())
                .andExpect(validPayload(new ClassPathResource("xsd/exchange-rate.xsd")))
                .andExpect(xpath("/r:GetExchangeRateResponse/r:baseCurrency", NAMESPACES).evaluatesTo("USD"))
                .andExpect(xpath("/r:GetExchangeRateResponse/r:quoteCurrency", NAMESPACES).evaluatesTo("VND"))
                .andExpect(xpath("/r:GetExchangeRateResponse/r:asOf", NAMESPACES)
                        .evaluatesTo("2026-11-13T05:00:00Z"));
    }

    @Test
    void invalidCurrencyFormatIsClientFaultWithoutEchoingInput() {
        // Không khớp [A-Z]{3} → PayloadValidatingInterceptor chặn trước endpoint, không kèm chi tiết của parser
        client.sendRequest(withPayload(request("us-d", "VND")))
                .andExpect(clientOrSenderFault())
                .andExpect(responseNotContaining("us-d"));
    }

    @Test
    void unsupportedPairIsClientFaultWithFixedMessage() {
        client.sendRequest(withPayload(request("XYZ", "VND")))
                .andExpect(clientOrSenderFault("Unsupported currency pair"))
                .andExpect(responseNotContaining("XYZ"));
    }

    @Test
    void unexpectedErrorIsServerFaultWithGenericMessage() {
        doThrow(new IllegalStateException("db password=hunter2")).when(mockExchangeRateService).quote(any(), any());

        client.sendRequest(withPayload(request("USD", "VND")))
                .andExpect(serverOrReceiverFault("Internal error"))
                .andExpect(responseNotContaining("hunter2"));
    }

    private static ResponseMatcher responseNotContaining(String text) {
        return (WebServiceMessage request, WebServiceMessage response) ->
                assertThat(asString(response)).doesNotContain(text);
    }

    private static String asString(WebServiceMessage message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            message.writeTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
