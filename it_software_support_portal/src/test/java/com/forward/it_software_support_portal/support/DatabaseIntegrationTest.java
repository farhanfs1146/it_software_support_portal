package com.forward.it_software_support_portal.support;

import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a database-backed integration test.
 *
 * <p>This exists as a composed annotation because {@code @EnabledIfDockerAvailable} is not
 * {@code @Inherited}: placing it on {@link AbstractIntegrationTest} alone would have no effect on
 * subclasses. Applying this annotation to each concrete test class guarantees the Docker check is
 * actually evaluated.
 *
 * <p>Without a Docker daemon these tests are reported as <strong>skipped</strong>, never as passed,
 * and they never fall back to a local database. A green build on a machine without Docker therefore
 * means "not verified", not "verified" - see docs/TESTING.md.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Testcontainers
@EnabledIfDockerAvailable
public @interface DatabaseIntegrationTest {
}
