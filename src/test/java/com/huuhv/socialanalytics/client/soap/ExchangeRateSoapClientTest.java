package com.huuhv.socialanalytics.client.soap;

import com.huuhv.socialanalytics.config.WebServiceClientConfig;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.webservices.client.WebServiceTemplateBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.xml.transform.StringSource;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.ws.test.client.RequestMatchers.payload;
import static org.springframework.ws.test.client.RequestMatchers.validPayload;
import static org.springframework.ws.test.client.ResponseCreators.withClientOrSenderFault;
import static org.springframework.ws.test.client.ResponseCreators.withPayload;

/**
 * Client dựng bằng đúng cấu hình production ({@code WebServiceClientConfig}: marshaller validate XSD, tắt DTD, timeout).
 */
class ExchangeRateSoapClientTest {

    private static final Instant NOW = Instant.parse("2026-11-13T05:42:17Z");
    private static final String NS = "urn:social-analytics:exchange-rate";

    private HttpServer httpServer;

    @AfterEach
    void stopServer() throws Exception {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private static ExchangeRateSoapClient client(URI url, Duration readTimeout) {
        AppProperties properties = new AppProperties(null, null, null, null, null, null, null,
                new AppProperties.Soap(new AppProperties.ExchangeRate(url, Duration.ofSeconds(1), readTimeout,
                        new AppProperties.MockEndpoint(false))));
        try {
            return new WebServiceClientConfig().exchangeRateSoapClient(new WebServiceTemplateBuilder(), properties,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ExchangeRateSoapClient client() {
        return client(URI.create("http://localhost:8080/services"), Duration.ofSeconds(1));
    }

    private static StringSource response(String base, String quote, String rate, String asOf) {
        return new StringSource("<GetExchangeRateResponse xmlns='" + NS + "'><baseCurrency>" + base
                + "</baseCurrency><quoteCurrency>" + quote + "</quoteCurrency><rate>" + rate + "</rate><asOf>"
                + asOf + "</asOf></GetExchangeRateResponse>");
    }

    @Test
    void returnsQuoteFromValidResponse() throws Exception {
        ExchangeRateSoapClient client = client();
        MockWebServiceServer server = MockWebServiceServer.createServer(client);
        server.expect(validPayload(new ClassPathResource("xsd/exchange-rate.xsd")))
                .andExpect(payload(new StringSource("<GetExchangeRateRequest xmlns='" + NS + "'><baseCurrency>USD"
                        + "</baseCurrency><quoteCurrency>VND</quoteCurrency></GetExchangeRateRequest>")))
                .andRespond(withPayload(response("USD", "VND", "25412.350000", "2026-11-13T05:00:00Z")));

        ExchangeRateQuote quote = client.getExchangeRate("USD", "VND");

        assertThat(quote).isEqualTo(new ExchangeRateQuote("USD", "VND", new BigDecimal("25412.350000"),
                Instant.parse("2026-11-13T05:00:00Z")));
        server.verify();
    }

    @Test
    void soapFaultBecomesUnavailableWithoutFaultString() throws Exception {
        ExchangeRateSoapClient client = client();
        MockWebServiceServer server = MockWebServiceServer.createServer(client);
        server.expect(validPayload(new ClassPathResource("xsd/exchange-rate.xsd")))
                .andRespond(withClientOrSenderFault("injected\nfault text", Locale.ENGLISH));

        assertThatThrownBy(() -> client.getExchangeRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .hasNoCause()
                .message().doesNotContain("injected");
    }

    @Test
    void rejectsResponseForAnotherPair() throws Exception {
        ExchangeRateSoapClient client = client();
        MockWebServiceServer.createServer(client).expect(validPayload(new ClassPathResource("xsd/exchange-rate.xsd")))
                .andRespond(withPayload(response("EUR", "VND", "27000", "2026-11-13T05:00:00Z")));

        assertThatThrownBy(() -> client.getExchangeRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsResponseViolatingSchema() throws Exception {
        // rate âm / quá 6 chữ số thập phân → marshaller validate XSD từ chối
        ExchangeRateSoapClient client = client();
        MockWebServiceServer server = MockWebServiceServer.createServer(client);
        server.expect(validPayload(new ClassPathResource("xsd/exchange-rate.xsd")))
                .andRespond(withPayload(response("USD", "VND", "-1", "2026-11-13T05:00:00Z")));
        server.expect(validPayload(new ClassPathResource("xsd/exchange-rate.xsd")))
                .andRespond(withPayload(response("USD", "VND", "1.1234567", "2026-11-13T05:00:00Z")));

        assertThatThrownBy(() -> client.getExchangeRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
        assertThatThrownBy(() -> client.getExchangeRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void rejectsAsOfWithoutTimeZoneOrInTheFuture() throws Exception {
        ExchangeRateSoapClient client = client();
        MockWebServiceServer server = MockWebServiceServer.createServer(client);
        server.expect(validPayload(new ClassPathResource("xsd/exchange-rate.xsd")))
                .andRespond(withPayload(response("USD", "VND", "25400", "2026-11-13T05:00:00")));
        server.expect(validPayload(new ClassPathResource("xsd/exchange-rate.xsd")))
                .andRespond(withPayload(response("USD", "VND", "25400", "2026-11-13T06:00:00Z")));

        assertThatThrownBy(() -> client.getExchangeRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .hasMessageContaining("asOf");
        assertThatThrownBy(() -> client.getExchangeRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .hasMessageContaining("future");
    }

    @Test
    void acceptsAsOfWithinAllowedClockSkew() throws Exception {
        ExchangeRateSoapClient client = client();
        MockWebServiceServer.createServer(client).expect(validPayload(new ClassPathResource("xsd/exchange-rate.xsd")))
                .andRespond(withPayload(response("USD", "VND", "25400", "2026-11-13T05:45:00Z")));

        assertThat(client.getExchangeRate("USD", "VND").asOf()).isEqualTo(Instant.parse("2026-11-13T05:45:00Z"));
    }

    @Test
    void rejectsInvalidCurrencyArgument() throws Exception {
        ExchangeRateSoapClient client = client();

        assertThatThrownBy(() -> client.getExchangeRate("usd", "VND")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getExchangeRate("USD", null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** XXE: response có DOCTYPE + external entity trỏ tới file cục bộ → bị từ chối, nội dung file không được đọc. */
    @Test
    void rejectsResponseWithDoctype(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-CONTENT");
        String body = "<?xml version='1.0'?><!DOCTYPE e [<!ENTITY xxe SYSTEM '" + secret.toUri() + "'>]>"
                + "<soapenv:Envelope xmlns:soapenv='http://schemas.xmlsoap.org/soap/envelope/'><soapenv:Body>"
                + "<GetExchangeRateResponse xmlns='" + NS + "'><baseCurrency>USD</baseCurrency>"
                + "<quoteCurrency>VND</quoteCurrency><rate>1</rate><asOf>&xxe;</asOf></GetExchangeRateResponse>"
                + "</soapenv:Body></soapenv:Envelope>";
        URI url = startServer(body, Duration.ZERO);

        assertThatThrownBy(() -> client(url, Duration.ofSeconds(2)).getExchangeRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .message().doesNotContain("TOP-SECRET");
    }

    @Test
    void readTimeoutBecomesUnavailable() throws Exception {
        URI url = startServer("never sent in time", Duration.ofSeconds(2));
        long started = System.nanoTime();

        assertThatThrownBy(() -> client(url, Duration.ofMillis(300)).getExchangeRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    private URI startServer(String body, Duration delay) throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/services", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                if (!delay.isZero()) {
                    Thread.sleep(delay.toMillis());
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // client đã ngắt kết nối (timeout) — không còn ai nhận response
            } finally {
                exchange.close();
            }
        });
        // Executor riêng: handler đang chờ không chặn thread dispatcher (stop() không phải đợi)
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
        return URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/services");
    }
}
