package com.forward.it_software_support_portal.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the SQL Hibernate actually sends, so a test can assert on the statement text itself.
 *
 * <p>Hibernate instantiates this from the {@code hibernate.session_factory.statement_inspector}
 * property set on {@link AbstractIntegrationTest}, which is why the buffer is static: the test has no
 * reference to the instance Hibernate created.
 *
 * <p><strong>Why not read the SQL log?</strong> Because an assertion that depends on logging
 * configuration can be "fixed" by changing logging - the same reason the performance tests count
 * statements through Hibernate {@code Statistics}. This inspector sits in the JDBC path, so what it
 * records is what the database receives.
 *
 * <p>Recording is <strong>off unless a test turns it on</strong>, and the buffer is cleared when it
 * does, so the rest of the suite pays nothing and cannot accumulate statements across hundreds of
 * tests. {@code CopyOnWriteArrayList} because the web container runs requests on other threads.
 */
public final class CapturingStatementInspector implements StatementInspector {

    private static final List<String> RECORDED = new CopyOnWriteArrayList<>();
    private static volatile boolean recording;

    @Override
    public String inspect(String sql) {
        if (recording) {
            RECORDED.add(sql);
        }
        return sql; // never rewrite the statement
    }

    /** Starts recording from an empty buffer. */
    static void start() {
        RECORDED.clear();
        recording = true;
    }

    /** Stops recording and returns what was captured. */
    static List<String> stop() {
        recording = false;
        return List.copyOf(RECORDED);
    }
}
