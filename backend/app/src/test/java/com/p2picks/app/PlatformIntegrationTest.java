package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.modules.bets.internal.ModuleDiscoveryProbe;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MvcResult;

class PlatformIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private ModuleDiscoveryProbe moduleDiscoveryProbe;

    @Test
    void exposesHealthWithoutDependencyDetails() {
        ResponseEntity<JsonNode> response = client.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.path("status").asText()).isEqualTo("UP");
    }

    @Test
    void developerDocsArePublicInDevWithoutOpeningOtherApis() throws Exception {
        mvc.perform(get("/docs")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/docs/"));
        mvc.perform(get("/docs/")).andExpect(status().isOk()).andExpect(forwardedUrl("/docs/index.html"));
        MvcResult home = mvc.perform(get("/docs/index.html")).andExpect(status().isOk()).andReturn();
        assertThat(home.getResponse().getContentAsString()).contains("Engineering handbook", "/docs/assets/");
        ResponseEntity<String> servedHome = client.getForEntity("/docs/", String.class);
        assertThat(servedHome.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(servedHome.getBody()).contains("Engineering handbook");
        ResponseEntity<String> architecture = client.getForEntity("/docs/architecture.html", String.class);
        assertThat(architecture.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(architecture.getBody()).contains("Architecture", "/docs/assets/");
        MvcResult reference = mvc.perform(get("/docs/source/openapi.yaml")).andExpect(status().isOk()).andReturn();
        assertThat(reference.getResponse().getContentAsString()).contains("openapi:");
        mvc.perform(get("/docs/source/.env.auth")).andExpect(status().isNotFound());
        mvc.perform(get("/tables")).andExpect(status().isUnauthorized());
    }

    @Test
    void connectsToPostgresRedisAndKafka() throws Exception {
        assertThat(moduleDiscoveryProbe).isNotNull();
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            assertThat(admin.describeCluster().clusterId().get(10, TimeUnit.SECONDS)).isNotBlank();
        }
    }
}
