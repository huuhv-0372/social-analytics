package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.client.soap.ExchangeRateSoapClient;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.soap.exchangerate.GetExchangeRateRequest;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.webservices.client.WebServiceMessageSenderFactory;
import org.springframework.boot.webservices.client.WebServiceTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;

import java.time.Clock;

/**
 * Client SOAP tỷ giá ({@link ExchangeRateSoapClient}) dựng từ {@link WebServiceTemplateBuilder} của Boot:
 * <ul>
 *     <li>URL lấy từ {@code app.soap.exchange-rate.url}; connect/read timeout từ cấu hình (OWASP A10), không theo
 *     redirect (không đi tới địa chỉ do server bên kia chỉ định).</li>
 *     <li>Marshaller JAXB tắt DTD + external entity (chống XXE — OWASP A05) và validate theo
 *     {@code xsd/exchange-rate.xsd} cả request lẫn response (response là dữ liệu không tin cậy).</li>
 *     <li>Message SOAP tạo bằng SAAJ (factory mặc định của template) — SAAJ từ chối message có DOCTYPE.</li>
 * </ul>
 */
@Configuration
public class WebServiceClientConfig {

    static final String EXCHANGE_RATE_SCHEMA = "xsd/exchange-rate.xsd";

    /** {@code public}: test dựng client bằng đúng cấu hình này (không cần Spring context). */
    @Bean
    public ExchangeRateSoapClient exchangeRateSoapClient(WebServiceTemplateBuilder builder,
                                                         AppProperties appProperties, Clock clock) throws Exception {
        AppProperties.ExchangeRate settings = appProperties.soap().exchangeRate();
        Jaxb2Marshaller marshaller = exchangeRateMarshaller();
        HttpClientSettings httpSettings = HttpClientSettings.defaults()
                .withTimeouts(settings.connectTimeout(), settings.readTimeout())
                .withRedirects(HttpRedirects.DONT_FOLLOW);
        ExchangeRateSoapClient client = new ExchangeRateSoapClient(clock);
        client.setWebServiceTemplate(builder
                .httpMessageSenderFactory(WebServiceMessageSenderFactory.http(ClientHttpRequestFactoryBuilder.jdk(),
                        httpSettings))
                .setMarshaller(marshaller)
                .setUnmarshaller(marshaller)
                .setDefaultUri(settings.url().toString())
                .build());
        return client;
    }

    /** Không đăng ký thành bean: chỉ client này dùng, tránh bị nhầm lấy cho mục đích khác. */
    static Jaxb2Marshaller exchangeRateMarshaller() throws Exception {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setPackagesToScan(GetExchangeRateRequest.class.getPackageName());
        marshaller.setSchema(new ClassPathResource(EXCHANGE_RATE_SCHEMA));
        // Mặc định của Spring đã tắt — khai báo tường minh để không phụ thuộc mặc định của version
        marshaller.setSupportDtd(false);
        marshaller.setProcessExternalEntities(false);
        marshaller.afterPropertiesSet();
        return marshaller;
    }
}
