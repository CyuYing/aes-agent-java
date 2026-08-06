package com.aes.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = SecurityIntegrationTest.TestApplication.class,
        properties = {
                "aes.security.enabled=true",
                "aes.security.username=teacher",
                "aes.security.password=test-only-strong-password"
        })
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void healthRemainsPublicButTheApplicationRequiresTeacherCredentials() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/").with(httpBasic("teacher", "wrong-password")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/").with(httpBasic("teacher", "test-only-strong-password")))
                .andExpect(status().isOk())
                .andExpect(content().string("secured-page"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, TestController.class})
    static class TestApplication {}

    @RestController
    static class TestController {
        @GetMapping("/")
        String index() {
            return "secured-page";
        }

        @GetMapping("/api/health")
        Map<String, String> health() {
            return Map.of("status", "UP");
        }
    }
}
