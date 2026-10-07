package com.forward.it_software_support_portal;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The original smoke test, now backed by a disposable PostgreSQL container instead of the
 * developer's local database.
 */
@DatabaseIntegrationTest
class ItSoftwareSupportPortalApplicationTests extends AbstractIntegrationTest {

    @Test
    @DisplayName("application context starts against a containerised database")
    void contextLoads() {
        assertThat(jdbc).isNotNull();
    }

    @Test
    @DisplayName("tests run against the container, not against a developer's local database")
    void usesContainerDatabaseNotLocalhostDevDatabase() {
        String url = jdbc.queryForObject("SELECT current_database()", String.class);

        // The container's database is named by Testcontainers, never 'ITSoftwareSupport'.
        assertThat(url)
                .as("test database must not be the local development database")
                .isNotEqualTo("ITSoftwareSupport");

        assertThat(POSTGRES.getJdbcUrl())
                .as("datasource must point at the mapped container port")
                .contains(String.valueOf(POSTGRES.getMappedPort(5432)));
    }
}
