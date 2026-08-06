package com.aes.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 单机便携版默认关闭鉴权；服务器部署可通过 AES_SECURITY_* 开启教师登录。
 * 网络部署仍应在反向代理上启用 HTTPS，避免 Basic 凭据明文传输。
 */
@Configuration
public class SecurityConfig {

    @Value("${aes.security.enabled:${AES_SECURITY_ENABLED:false}}")
    private boolean securityEnabled;

    @Value("${aes.security.username:${AES_SECURITY_USERNAME:teacher}}")
    private String username;

    @Value("${aes.security.password:${AES_SECURITY_PASSWORD:}}")
    private String password;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 页面通过 multipart POST 与 SSE 调用同源 API，采用 Basic 登录时关闭 CSRF。
        http.csrf(csrf -> csrf.disable());
        if (securityEnabled) {
            validateCredentials();
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/health").permitAll()
                            .anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults());
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        validateUsername();
        String configuredPassword = password == null ? "" : password;
        return new InMemoryUserDetailsManager(User.withUsername(username.trim())
                .password(passwordEncoder.encode(configuredPassword))
                .roles("TEACHER")
                .build());
    }

    private void validateCredentials() {
        validateUsername();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "已启用访问鉴权，但 AES_SECURITY_PASSWORD 为空");
        }
    }

    private void validateUsername() {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("AES_SECURITY_USERNAME 不能为空");
        }
    }
}
