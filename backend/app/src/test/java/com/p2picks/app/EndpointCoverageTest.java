package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.p2picks.app.common.RateLimits;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Guards against drift between the running endpoints, the OpenAPI contract and the rate limits: every endpoint is
 * in the contract and every contract operation is served (except the few listed below), and every write endpoint
 * has a rate rule or a documented exemption ({@link RateLimits#EXEMPT}).
 */
class EndpointCoverageTest extends IntegrationTestSupport {
    private static final AntPathMatcher PATHS = new AntPathMatcher();
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Contract operations on the roadmap but not built yet (Quick Pick subscriptions and copies). */
    private static final Set<String> PLANNED = Set.of(
            "POST /tables/*/quick-picks/subscribe",
            "DELETE /tables/*/quick-picks/subscribe",
            "POST /bets/*/wash");

    /** Dev-only endpoints are off in the test context (their features are disabled), so they may be missing. */
    private static boolean devOnly(String endpoint) {
        return endpoint.substring(endpoint.indexOf(' ') + 1).startsWith("/dev/");
    }

    /** Served but deliberately outside the API contract (the developer docs site). */
    private static final Set<String> UNDOCUMENTED = Set.of("GET /docs", "GET /docs/");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    /** "METHOD /path/*" for every handler, path variables as {@code *}. */
    private Set<String> served() {
        Set<String> served = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            Class<?> type = handler.getBeanType();
            // Test-only controllers (IntegrationTestSupport's security probe) are nested in test classes.
            if (!type.getPackageName().startsWith("com.p2picks.app") || type.getEnclosingClass() != null) return;
            for (String pattern : info.getPatternValues()) {
                for (var method : info.getMethodsCondition().getMethods()) {
                    served.add(method.name() + " " + stars(pattern));
                }
            }
        });
        return served;
    }

    private static String stars(String pattern) {
        return pattern.replaceAll("\\{[^}]+}", "*");
    }

    @Test
    void everyEndpointIsInTheContractAndEveryOperationIsServed() throws IOException {
        Set<String> contract = new TreeSet<>();
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream("/openapi.yaml"))) {
            JsonNode paths = new ObjectMapper(new YAMLFactory()).readTree(input).path("paths");
            paths.fields().forEachRemaining(path -> path.getValue().fieldNames().forEachRemaining(method -> {
                if (Set.of("get", "post", "put", "patch", "delete").contains(method)) {
                    contract.add(method.toUpperCase() + " " + stars(path.getKey()));
                }
            }));
        }
        Set<String> served = served();

        Set<String> undocumented = new TreeSet<>(served);
        undocumented.removeAll(contract);
        undocumented.removeAll(UNDOCUMENTED);
        assertThat(undocumented).as("served endpoints missing from openapi.yaml").isEmpty();

        Set<String> unserved = new TreeSet<>(contract);
        unserved.removeAll(served);
        unserved.removeIf(EndpointCoverageTest::devOnly);
        assertThat(unserved).as("openapi.yaml operations nobody serves (add to PLANNED only if on the roadmap)")
                .containsExactlyInAnyOrderElementsOf(PLANNED);
    }

    @Test
    void everyWriteEndpointHasARateRuleOrADocumentedExemption() {
        Set<String> writes = new TreeSet<>();
        for (String endpoint : served()) {
            if (WRITES.contains(endpoint.substring(0, endpoint.indexOf(' ')))) writes.add(endpoint);
        }
        Set<String> uncovered = new TreeSet<>();
        Set<String> doubled = new TreeSet<>();
        for (String endpoint : writes) {
            String method = endpoint.substring(0, endpoint.indexOf(' '));
            String path = endpoint.substring(endpoint.indexOf(' ') + 1);
            boolean ruled = RateLimits.RULES.stream()
                    .anyMatch(rule -> rule.methods().contains(method) && PATHS.match(rule.pattern(), path));
            boolean exempt = RateLimits.EXEMPT.containsKey(endpoint);
            if (!ruled && !exempt) uncovered.add(endpoint);
            if (ruled && exempt) doubled.add(endpoint);
        }
        assertThat(uncovered).as("write endpoints with no rate rule and no RateLimits.EXEMPT entry").isEmpty();
        assertThat(doubled).as("endpoints both rate-ruled and exempt").isEmpty();

        Set<String> stale = new TreeSet<>(RateLimits.EXEMPT.keySet());
        stale.removeAll(writes);
        stale.removeIf(EndpointCoverageTest::devOnly);
        assertThat(stale).as("RateLimits.EXEMPT entries for endpoints that don't exist").isEmpty();
        for (Map.Entry<String, String> exemption : RateLimits.EXEMPT.entrySet()) {
            assertThat(exemption.getValue()).as("reason for " + exemption.getKey()).isNotBlank();
        }
        assertThat(RateLimits.BACKSTOP.methods()).containsExactlyInAnyOrderElementsOf(WRITES);
    }
}
