package com.p2picks.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers
@AutoConfigureMockMvc
class AdminHealthIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    @Container
    private static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("pvpicks.bet-lock-worker.enabled", () -> "false");
        registry.add("pvpicks.outbox-relay.enabled", () -> "false");
        registry.add("pvpicks.outbox-cleanup.enabled", () -> "false");
        // Never call the real sports provider from tests, even if a key is exported in the shell.
        registry.add("pvpicks.sports.balldontlie.api-key", () -> "");
        registry.add("PVPICKS_DEV_JWT_SECRET", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("PVPICKS_DEV_IDEMPOTENCY_SECRET", () -> "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void reportsActualRedisOutageWithoutExposingErrorDetails() throws Exception {
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'health_admin', 1, ?, 'ADMIN', 'ACTIVE')
                """, UUID.randomUUID(), passwords.encode("a secure test password"));
        MvcResult csrf = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        String csrfToken = json.readTree(csrf.getResponse().getContentAsString()).path("token").asText();
        MvcResult login = mvc.perform(post("/auth/login")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrfToken).cookie(csrfCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"health_admin\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie session = login.getResponse().getCookie("session");
        assertThat(session).isNotNull();
        mvc.perform(get("/admin/health").cookie(session)).andExpect(status().isOk());

        redis.stop();

        MvcResult response = mvc.perform(get("/admin/health").cookie(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshot = json.readTree(response.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("AdminHealthSnapshot").validate(snapshot)).isEmpty();
        assertThat(snapshot.path("postgres").asText()).isEqualTo("UP");
        assertThat(snapshot.path("redis").asText()).isEqualTo("DOWN");
        assertThat(snapshot.path("kafka").asText()).isEqualTo("NOT_CHECKED");
        mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
    }
}
