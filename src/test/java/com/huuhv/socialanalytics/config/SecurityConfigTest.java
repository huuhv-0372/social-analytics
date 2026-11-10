package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void plainPasswordIsEncoded() {
        UserDetails user = load("plain-secret");

        assertThat(user.getPassword()).startsWith("{bcrypt}").isNotEqualTo("plain-secret");
        assertThat(ENCODER.matches("plain-secret", user.getPassword())).isTrue();
    }

    @Test
    void preEncodedPasswordIsKeptAsIs() {
        String hashed = ENCODER.encode("hashed-secret");

        UserDetails user = load(hashed);

        assertThat(user.getPassword()).isEqualTo(hashed);
        assertThat(ENCODER.matches("hashed-secret", user.getPassword())).isTrue();
    }

    @Test
    void missingPasswordStopsStartup() {
        assertThatThrownBy(() -> securityConfig.apiUserDetailsService(new SecurityProperties()))
                .isInstanceOf(IllegalStateException.class);
    }

    private UserDetails load(String password) {
        SecurityProperties properties = new SecurityProperties();
        properties.getUser().setName("dev@example.com");
        properties.getUser().setPassword(password);
        return securityConfig.apiUserDetailsService(properties).loadUserByUsername("dev@example.com");
    }
}
