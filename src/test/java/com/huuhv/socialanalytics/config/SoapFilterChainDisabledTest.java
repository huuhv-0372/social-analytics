package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.controller.web.LoginController;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mặc định production ({@code app.soap.exchange-rate.mock-endpoint.enabled=false}): không có chain SOAP riêng →
 * {@code /services/**} thuộc chain chính (deny-by-default + CSRF), như mọi đường dẫn khác.
 */
@WebMvcTest(controllers = LoginController.class,
        properties = AppProperties.ExchangeRate.MOCK_ENDPOINT_ENABLED_PROPERTY + "=false")
@Import({SecurityConfig.class, WebConfig.class})
@ActiveProfiles("test")
class SoapFilterChainDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @Test
    void soapFilterChainIsNotRegistered() {
        assertThat(applicationContext.containsBean("soapFilterChain")).isFalse();
    }

    @Test
    void soapPathRequiresLoginAndCsrf() throws Exception {
        mockMvc.perform(get("/services/exchangeRate.wsdl"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(post("/services").contentType(MediaType.TEXT_XML).content("<x/>"))
                .andExpect(status().isForbidden());
    }
}
