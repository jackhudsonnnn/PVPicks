package com.p2picks.app.config;

import com.p2picks.app.common.AppMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppModeTest {

    @Test
    void readsTheDevAndLanProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        assertEquals(AppMode.DEV, AppMode.of(environment));
        assertEquals("dev", AppMode.of(environment).id());

        environment.setActiveProfiles("lan");
        assertEquals(AppMode.LAN, AppMode.of(environment));
    }

    @Test
    void rejectsMissingMixedOrUnconfiguredProfiles() {
        MockEnvironment environment = new MockEnvironment();
        assertThrows(IllegalStateException.class, () -> AppMode.of(environment));

        environment.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> AppMode.of(environment));

        environment.setActiveProfiles("dev", "lan");
        assertThrows(IllegalStateException.class, () -> AppMode.of(environment));

        environment.setActiveProfiles("dev", "prod");
        assertThrows(IllegalStateException.class, () -> AppMode.of(environment));
    }

    @Test
    void doesNotMigrateWithMixedProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");
        Flyway flyway = mock(Flyway.class);

        assertThrows(IllegalStateException.class,
                () -> new ModeConfiguration().checkedMigrationStrategy(environment).migrate(flyway));
        verify(flyway, never()).migrate();
    }
}
