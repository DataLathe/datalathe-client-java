package com.datalathe.client.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChipResolverLoggingTest {

    private static final class CapturingAppender extends AbstractAppender {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("capture", null, null, true, Property.EMPTY_ARRAY);
            start();
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private static final ChipFactory FACTORY = new ChipFactory() {
        @Override
        public boolean isPartitioned(String table) {
            return false;
        }

        @Override
        public ChipSource buildSource(String table, String partitionValue) {
            return ChipSource.builder()
                    .sourceType(SourceType.MYSQL)
                    .databaseName("db")
                    .tableName(table)
                    .query("SELECT * FROM " + table)
                    .build();
        }
    };

    private MockWebServer server;
    private DatalatheClient client;
    private Logger logger;
    private Level previousLevel;
    private CapturingAppender appender;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
        logger = (Logger) LogManager.getLogger(ChipResolver.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new CapturingAppender();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.removeAppender(appender);
        logger.setLevel(previousLevel);
        server.shutdown();
    }

    private LogEvent resolveAndCaptureFailureEvent(String errorBody) throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chips\":[],\"metadata\":[]}"));
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody(errorBody));

        new ChipResolver(client).resolveForTables(
                Set.of("users"), List.of(), "tenant", "42", FACTORY);

        List<LogEvent> failures = appender.events.stream()
                .filter(e -> !e.getMessage().getFormattedMessage().startsWith("Creating chip"))
                .toList();
        assertEquals(1, failures.size());
        return failures.get(0);
    }

    @Test
    void emptySourceLogsInfoWithoutStack() throws Exception {
        LogEvent event = resolveAndCaptureFailureEvent(
                "{\"error_code\":\"EMPTY_SOURCE\",\"message\":\"source returned no rows\"}");

        assertEquals(Level.INFO, event.getLevel());
        assertNull(event.getThrown());
        String message = event.getMessage().getFormattedMessage();
        assertTrue(message.contains("table=users"));
        assertTrue(message.contains("message=source returned no rows"));
    }

    @Test
    void legacyEmptyMessageLogsInfoWithoutStack() throws Exception {
        LogEvent event = resolveAndCaptureFailureEvent(
                "{\"error_code\":\"PROCESSING_ERROR\","
                        + "\"message\":\"No partitions to register - all partition values returned empty data\"}");

        assertEquals(Level.INFO, event.getLevel());
        assertNull(event.getThrown());
        String message = event.getMessage().getFormattedMessage();
        assertTrue(message.contains("table=users"));
        assertTrue(message.contains("message=No partitions to register"));
    }

    @Test
    void otherApiErrorLogsWarnWithoutStack() throws Exception {
        LogEvent event = resolveAndCaptureFailureEvent(
                "{\"error_code\":\"PROCESSING_ERROR\",\"message\":\"staging query failed\"}");

        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrown());
        String message = event.getMessage().getFormattedMessage();
        assertTrue(message.contains("table=users"));
        assertTrue(message.contains("errorCode=PROCESSING_ERROR"));
        assertTrue(message.contains("message=staging query failed"));
    }

    @Test
    void unstructuredFailureKeepsErrorWithStack() throws Exception {
        LogEvent event = resolveAndCaptureFailureEvent("Internal Server Error");

        assertEquals(Level.ERROR, event.getLevel());
        assertNotNull(event.getThrown());
    }
}
