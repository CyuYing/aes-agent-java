package com.aes.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    @Test
    void storesTheConfiguredTeacherPasswordAsABcryptHash() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "username", "teacher");
        ReflectionTestUtils.setField(config, "password", "strong-password");
        var encoder = config.passwordEncoder();

        UserDetails user = config.userDetailsService(encoder)
                .loadUserByUsername("teacher");

        assertThat(user.getPassword()).doesNotContain("strong-password");
        assertThat(encoder.matches("strong-password", user.getPassword())).isTrue();
        assertThat(user.getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_TEACHER");
    }

    @Test
    void rejectsABlankTeacherName() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "username", "  ");
        ReflectionTestUtils.setField(config, "password", "strong-password");

        assertThatThrownBy(() -> config.userDetailsService(config.passwordEncoder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USERNAME");
    }
}
