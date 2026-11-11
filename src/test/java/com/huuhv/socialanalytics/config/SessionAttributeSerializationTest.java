package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.service.AppOAuth2User;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.io.File;
import java.io.Serializable;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attribute mà app thực sự đặt vào session (SecurityContext sau đăng nhập, request OAuth2 đang chờ callback, saved
 * request) phải đi qua được bộ lọc deserialize; mọi thứ khác bị loại thành {@code null} thay vì ném lỗi.
 */
class SessionAttributeSerializationTest {

    private final ConversionService conversionService =
            SessionAttributeSerialization.conversionService(getClass().getClassLoader());

    @Test
    void securityContextWithAppPrincipalRoundTrips() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", "1234567890");
        attributes.put("name", "Nguyễn Văn A");
        attributes.put("email", "a@example.com");
        // Thuộc tính JSON lồng nhau / kiểu số mà provider có thể trả về
        attributes.put("picture", Map.of("data", Map.of("url", "https://example.com/a.png", "height", 50,
                "is_silhouette", false)));
        attributes.put("big_number", new BigInteger("123456789012345678901234567890"));
        attributes.put("ratio", 1.5d);
        attributes.put("tags", List.of("a", "b"));
        List<GrantedAuthority> authorities = List.of(
                new OAuth2UserAuthority(attributes, "id"),
                new SimpleGrantedAuthority("SCOPE_public_profile"),
                FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
                        .issuedAt(Instant.parse("2026-11-11T07:00:00.123456Z"))
                        .build());
        AppOAuth2User principal = new AppOAuth2User(authorities, attributes, "id", 42L, "Nguyễn Văn A");
        OAuth2AuthenticationToken authentication =
                new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "facebook");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        authentication.setDetails(new WebAuthenticationDetails(request));
        SecurityContextImpl context = new SecurityContextImpl(authentication);

        Object restored = roundTrip(context);

        assertThat(restored).isEqualTo(context);
        AppOAuth2User restoredPrincipal =
                (AppOAuth2User) ((SecurityContextImpl) restored).getAuthentication().getPrincipal();
        assertThat(restoredPrincipal.getUserId()).isEqualTo(42L);
        assertThat(restoredPrincipal.getDisplayName()).isEqualTo("Nguyễn Văn A");
    }

    @Test
    void pendingOAuth2AuthorizationRequestWithPkceRoundTrips() {
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(
                CommonOAuth2Provider.FACEBOOK.getBuilder("facebook")
                        .clientId("client-id").clientSecret("client-secret").build());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/facebook");
        request.setServletPath("/oauth2/authorization/facebook");
        OAuth2AuthorizationRequest authorizationRequest = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, "/oauth2/authorization").resolve(request);
        assertThat(authorizationRequest).isNotNull();
        assertThat(authorizationRequest.<String>getAttribute(PkceParameterNames.CODE_VERIFIER)).isNotBlank();

        Object restored = roundTrip(authorizationRequest);

        assertThat(restored).usingRecursiveComparison().isEqualTo(authorizationRequest);
    }

    @Test
    void savedRequestRoundTrips() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
        request.setQueryString("page=1");
        request.setParameter("page", "1");
        request.addHeader("Accept", "text/html");
        request.addHeader("User-Agent", "JUnit");
        request.setCookies(new Cookie("XSRF-TOKEN", "token"));
        request.addPreferredLocale(Locale.forLanguageTag("vi-VN"));
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.saveRequest(request, new MockHttpServletResponse());
        SavedRequest savedRequest = requestCache.getRequest(request, new MockHttpServletResponse());
        assertThat(savedRequest).isInstanceOf(DefaultSavedRequest.class);

        Object restored = roundTrip(savedRequest);

        assertThat(restored).usingRecursiveComparison().isEqualTo(savedRequest);
    }

    @Test
    void classOutsideWhitelistIsDiscarded() {
        assertThat(roundTrip(new NotWhitelisted("payload"))).isNull();
        // Class JDK ngoài các package cho phép
        assertThat(roundTrip(new File("x"))).isNull();
        // Lồng trong collection được phép cũng bị chặn
        assertThat(roundTrip(new ArrayList<>(List.of(new NotWhitelisted("nested"))))).isNull();
    }

    @Test
    void deeplyNestedGraphIsDiscarded() {
        List<Object> root = new ArrayList<>();
        List<Object> current = root;
        for (int i = 0; i < 100; i++) {
            List<Object> child = new ArrayList<>();
            current.add(child);
            current = child;
        }

        assertThat(roundTrip(root)).isNull();
    }

    @Test
    void corruptedBytesAreDiscarded() {
        byte[] bytes = conversionService.convert("value", byte[].class);
        byte[] truncated = Arrays.copyOf(bytes, bytes.length / 2);

        assertThat(conversionService.convert(truncated, Object.class)).isNull();
        assertThat(conversionService.convert(new byte[]{1, 2, 3}, Object.class)).isNull();
    }

    private Object roundTrip(Object value) {
        byte[] bytes = conversionService.convert(value, byte[].class);
        return conversionService.convert(bytes, Object.class);
    }

    private record NotWhitelisted(String value) implements Serializable {
    }
}
