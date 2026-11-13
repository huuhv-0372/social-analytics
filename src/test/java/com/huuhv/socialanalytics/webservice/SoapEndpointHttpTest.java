package com.huuhv.socialanalytics.webservice;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.client.soap.ExchangeRateQuote;
import com.huuhv.socialanalytics.config.WebServiceClientConfig;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webservices.client.WebServiceTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Endpoint SOAP mock trên Tomcat thật (profile test bật {@code app.soap.exchange-rate.mock-endpoint.enabled}):
 * chain bảo mật riêng (public, stateless, không CSRF), WSDL, chống XXE, và client production gọi endpoint qua HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SoapEndpointHttpTest {

    private static final String NS = ExchangeRateEndpoint.NAMESPACE;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    private int port;

    @Autowired
    private Clock clock;

    private static String envelope(String prolog, String body) {
        return prolog + "<soapenv:Envelope xmlns:soapenv='http://schemas.xmlsoap.org/soap/envelope/'><soapenv:Body>"
                + body + "</soapenv:Body></soapenv:Envelope>";
    }

    private static String rateRequest(String base) {
        return "<GetExchangeRateRequest xmlns='" + NS + "'><baseCurrency>" + base
                + "</baseCurrency><quoteCurrency>VND</quoteCurrency></GetExchangeRateRequest>";
    }

    private HttpResponse<String> postSoap(String xml) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/services"))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"\"")
                .POST(HttpRequest.BodyPublishers.ofString(xml))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anonymousPostWithoutCsrfIsServedStatelessly() throws Exception {
        HttpResponse<String> response = postSoap(envelope("", rateRequest("USD")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("GetExchangeRateResponse").contains(">USD<");
        // Stateless: không tạo session, không phát cookie CSRF
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Content-Security-Policy"))
                .hasValue("default-src 'none'; frame-ancestors 'none'");
    }

    @Test
    void wsdlIsPublicAndPointsToRequestAddress() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/services/exchangeRate.wsdl")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("ExchangeRatePort")
                .contains("location=\"http://localhost:" + port + "/services\"");
    }

    /** XXE: DOCTYPE + external entity trỏ tới file cục bộ → bị từ chối, nội dung file không lọt ra response. */
    @Test
    void requestWithDoctypeIsRejectedWithoutResolvingEntity(@TempDir Path dir, CapturedOutput output)
            throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-CONTENT");
        String xml = envelope("<?xml version='1.0'?><!DOCTYPE e [<!ENTITY xxe SYSTEM '" + secret.toUri() + "'>]>",
                rateRequest("&xxe;"));

        HttpResponse<String> response = postSoap(xml);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("TOP-SECRET");
        assertThat(output).contains("Rejected unreadable SOAP request")
                .doesNotContain("TOP-SECRET")
                .doesNotContain("Servlet.service()");
    }

    @Test
    void malformedXmlIsBadRequest() throws Exception {
        assertThat(postSoap("<soapenv:Envelope").statusCode()).isEqualTo(400);
    }

    @Test
    void productionClientGetsRateFromEndpointOverHttp() throws Exception {
        AppProperties properties = new AppProperties(null, null, null, null, null, null, null,
                new AppProperties.Soap(new AppProperties.ExchangeRate(
                        URI.create("http://localhost:" + port + "/services"), Duration.ofSeconds(2),
                        Duration.ofSeconds(3), new AppProperties.MockEndpoint(true))));

        ExchangeRateQuote quote = new WebServiceClientConfig()
                .exchangeRateSoapClient(new WebServiceTemplateBuilder(), properties, clock)
                .getExchangeRate("USD", "VND");

        assertThat(quote.baseCurrency()).isEqualTo("USD");
        assertThat(quote.quoteCurrency()).isEqualTo("VND");
        assertThat(quote.rate()).isPositive();
        assertThat(quote.asOf()).isBeforeOrEqualTo(clock.instant());
    }
}
