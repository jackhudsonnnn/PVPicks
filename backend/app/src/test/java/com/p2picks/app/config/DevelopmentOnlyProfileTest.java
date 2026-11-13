package com.p2picks.app.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevelopmentOnlyProfileTest {

    @Test
    void acceptsOnlyTheDevProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertDoesNotThrow(() -> new DevelopmentOnlyProfile(environment));
    }

    @Test
    void rejectsMissingOrUnimplementedProfiles() {
        MockEnvironment environment = new MockEnvironment();
        assertThrows(IllegalStateException.class, () -> new DevelopmentOnlyProfile(environment));

        environment.setActiveProfiles("lan");
        assertThrows(IllegalStateException.class, () -> new DevelopmentOnlyProfile(environment));

        environment.setActiveProfiles("dev", "prod");
        assertThrows(IllegalStateException.class, () -> new DevelopmentOnlyProfile(environment));
    }

    @Test
    void doesNotMigrateWithMixedProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");
        Flyway flyway = mock(Flyway.class);

        assertThrows(IllegalStateException.class,
                () -> new DevelopmentFlywayConfiguration().developmentMigrationStrategy(environment).migrate(flyway));
        verify(flyway, never()).migrate();
    }
}
