package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.security.RequestBodySizeLimitFilter;
import com.huuhv.socialanalytics.webservice.ExchangeRateEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.SoapMessageCreationException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;
import org.springframework.ws.soap.server.endpoint.SoapFaultMappingExceptionResolver;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.transport.http.WebServiceMessageReceiverHandlerAdapter;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

import java.io.IOException;
import java.util.List;

/**
 * Endpoint SOAP mock tỷ giá do app tự host ({@link ExchangeRateEndpoint}). {@code MessageDispatcherServlet} +
 * {@code @EnableWs} do Boot tự cấu hình ở {@code spring.webservices.path} (mặc định {@code /services}); class này chỉ
 * chạy khi bật {@code app.soap.exchange-rate.mock-endpoint.enabled}:
 * <ul>
 *     <li>WSDL sinh từ XSD: {@code GET /services/exchangeRate.wsdl} (tên bean = tên file).</li>
 *     <li>Validate request/response theo XSD (OWASP A06 — whitelist định dạng); request sai → SOAP Fault {@code Client}
 *     thông điệp chung, không kèm chi tiết lỗi validate (chi tiết của parser echo lại giá trị người gọi gửi lên).</li>
 *     <li>Exception không lường trước → SOAP Fault {@code Server} thông điệp chung + log WARN (thay
 *     {@code SimpleSoapExceptionResolver} của Spring-WS — resolver đó đưa {@code ex.getMessage()} vào fault string).</li>
 * </ul>
 * XXE (OWASP A05): message SOAP được SAAJ đọc ({@code SaajSoapMessageFactory}) — SAAJ từ chối message có DOCTYPE nên
 * không có DTD/external entity nào được xử lý; request đó (và XML hỏng) nhận 400.
 */
@Configuration
@ConditionalOnBooleanProperty(AppProperties.ExchangeRate.MOCK_ENDPOINT_ENABLED_PROPERTY)
public class WebServiceConfig implements WsConfigurer {

    static final String GENERIC_SERVER_FAULT = "Internal error";

    @Bean
    XsdSchema exchangeRateSchema() {
        return new SimpleXsdSchema(new ClassPathResource(WebServiceClientConfig.EXCHANGE_RATE_SCHEMA));
    }

    /** Tên bean {@code exchangeRate} → WSDL ở {@code /services/exchangeRate.wsdl}. */
    @Bean(name = "exchangeRate")
    DefaultWsdl11Definition exchangeRateWsdl(XsdSchema exchangeRateSchema) {
        DefaultWsdl11Definition definition = new DefaultWsdl11Definition();
        definition.setPortTypeName("ExchangeRatePort");
        definition.setServiceName("ExchangeRateService");
        // Đường dẫn tương đối; địa chỉ đầy đủ theo request nhờ spring.webservices.servlet.init.transformWsdlLocations
        definition.setLocationUri("/services");
        definition.setTargetNamespace(ExchangeRateEndpoint.NAMESPACE);
        definition.setSchema(exchangeRateSchema);
        return definition;
    }

    /**
     * Thay {@code SimpleSoapExceptionResolver} (order thấp nhất) cho mọi exception không có {@code @SoapFault};
     * exception có {@code @SoapFault} vẫn do {@code SoapFaultAnnotationExceptionResolver} (order 0) xử lý trước.
     */
    @Bean
    SoapFaultMappingExceptionResolver genericSoapFaultExceptionResolver() {
        SoapFaultDefinition fault = new SoapFaultDefinition();
        fault.setFaultCode(SoapFaultDefinition.SERVER);
        fault.setFaultStringOrReason(GENERIC_SERVER_FAULT);
        SoapFaultMappingExceptionResolver resolver = new SoapFaultMappingExceptionResolver();
        resolver.setDefaultFault(fault);
        // Log WARN kèm exception (chi tiết kỹ thuật chỉ ghi log — OWASP A10)
        resolver.setWarnLogCategory(ExchangeRateEndpoint.class.getName());
        resolver.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return resolver;
    }

    /**
     * Thay adapter mặc định của {@code MessageDispatcherServlet} (servlet tìm bean theo đúng tên này). Khi đã khai
     * báo bean, servlet không tự gắn message factory → gắn {@code SaajSoapMessageFactory} như mặc định.
     */
    @Bean(name = "messageReceiverHandlerAdapter")
    WebServiceMessageReceiverHandlerAdapter messageReceiverHandlerAdapter() throws Exception {
        SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory();
        messageFactory.afterPropertiesSet();
        BadRequestOnUnreadableMessageAdapter adapter = new BadRequestOnUnreadableMessageAdapter();
        adapter.setMessageFactory(messageFactory);
        return adapter;
    }

    /**
     * Request không đọc được thành message SOAP (XML hỏng, có DOCTYPE — SAAJ từ chối để chống XXE) là lỗi của
     * client: trả 400 không body + 1 dòng WARN, thay vì để exception lan ra servlet container (500 + log ERROR kèm
     * stack trace cho mỗi request rác). Không log thông điệp của exception (có thể chứa nội dung request).
     */
    @Slf4j
    static final class BadRequestOnUnreadableMessageAdapter extends WebServiceMessageReceiverHandlerAdapter {

        @Override
        public ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            try {
                return super.handle(request, response, handler);
            } catch (SoapMessageCreationException | IOException e) {
                if (response.isCommitted()) {
                    throw e;
                }
                // Body chunked vượt mock-endpoint.max-request-size (RequestBodySizeLimitFilter ném khi đang đọc) — SAAJ
                // bọc lỗi đọc thành lỗi tạo message mà không giữ cause → nhận ra qua dấu filter đặt trên request
                if (RequestBodySizeLimitFilter.isBodyTooLarge(request)) {
                    response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
                    return null;
                }
                if (e instanceof IOException) {
                    throw e;
                }
                log.warn("Rejected unreadable SOAP request ({})", e.getClass().getSimpleName());
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return null;
            }
        }

    }

    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        PayloadValidatingInterceptor validating = new PayloadValidatingInterceptor();
        validating.setXsdSchema(exchangeRateSchema());
        validating.setValidateRequest(true);
        validating.setValidateResponse(true);
        // Không đưa thông điệp của parser (chứa giá trị người gọi gửi) vào fault detail
        validating.setAddValidationErrorDetail(false);
        try {
            validating.afterPropertiesSet(); // không phải bean → tự khởi tạo validator
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize SOAP payload validation", e);
        }
        interceptors.add(validating);
    }
}
