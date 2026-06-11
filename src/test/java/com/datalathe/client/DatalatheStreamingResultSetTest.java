package com.datalathe.client;

import com.datalathe.client.results.DatalatheStreamingResultSet;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatalatheStreamingResultSetTest {
    private MockWebServer server;
    private DatalatheClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new DatalatheClient(server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse ndjson(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(body);
    }

    @Test
    void happyPathSchemaTwoBatchesEnd() throws Exception {
        String body = String.join("\n",
                "{\"type\":\"schema\",\"schema\":[{\"name\":\"id\",\"data_type\":\"Int32\"},"
                        + "{\"name\":\"name\",\"data_type\":\"Utf8\"}]}",
                "{\"type\":\"rows\",\"rows\":[[\"1\",\"alice\"],[\"2\",\"bob\"]]}",
                "{\"type\":\"rows\",\"rows\":[[\"3\",\"carol\"]]}",
                "{\"type\":\"end\",\"row_count\":3,\"timing\":{\"total_ms\":10}}") + "\n";
        server.enqueue(ndjson(body));

        int count = 0;
        try (DatalatheStreamingResultSet rs = client.generateReportStream(
                Arrays.asList("chip1"), "SELECT id, name FROM t")) {

            ResultSetMetaData md = rs.getMetaData();
            assertEquals(2, md.getColumnCount());
            assertEquals("id", md.getColumnName(1));
            assertEquals("name", md.getColumnName(2));

            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals("alice", rs.getString(2));
            assertEquals("alice", rs.getString("name"));
            count++;

            assertTrue(rs.next());
            assertEquals(2, rs.getInt("id"));
            assertEquals("bob", rs.getString(2));
            count++;

            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            assertEquals("carol", rs.getString(2));
            count++;

            assertFalse(rs.next());
            assertEquals(3, count);
            assertEquals(3L, rs.getRowCount());
        }

        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().endsWith("/lathe/report"));
        String sent = req.getBody().readUtf8();
        assertTrue(sent.contains("\"stream\":true"), "stream flag must be set");
        assertTrue(sent.contains("\"query\":[\"SELECT id, name FROM t\"]"));
    }

    @Test
    void forwardOnlyMethodsThrow() throws Exception {
        String body = String.join("\n",
                "{\"type\":\"schema\",\"schema\":[{\"name\":\"id\",\"data_type\":\"Int32\"}]}",
                "{\"type\":\"rows\",\"rows\":[[\"1\"]]}",
                "{\"type\":\"end\",\"row_count\":1}") + "\n";
        server.enqueue(ndjson(body));

        try (DatalatheStreamingResultSet rs = client.generateReportStream(
                Arrays.asList("chip1"), "SELECT id FROM t")) {
            assertThrows(SQLFeatureNotSupportedException.class, rs::previous);
            assertThrows(SQLFeatureNotSupportedException.class, rs::first);
            assertThrows(SQLFeatureNotSupportedException.class, rs::last);
            assertThrows(SQLFeatureNotSupportedException.class, () -> rs.absolute(1));
            assertThrows(SQLFeatureNotSupportedException.class, () -> rs.relative(1));
            assertThrows(SQLFeatureNotSupportedException.class, rs::beforeFirst);
            assertThrows(SQLFeatureNotSupportedException.class, rs::afterLast);
        }
    }

    @Test
    void multiQueryRejectedClientSide() {
        List<String> queries = Arrays.asList("SELECT 1", "SELECT 2");
        assertThrows(IllegalArgumentException.class,
                () -> client.generateReportStream(Arrays.asList("chip1"), queries));
        // No request should have been issued.
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void errorFrameThrowsFromNext() throws Exception {
        String body = String.join("\n",
                "{\"type\":\"schema\",\"schema\":[{\"name\":\"id\",\"data_type\":\"Int32\"}]}",
                "{\"type\":\"rows\",\"rows\":[[\"1\"]]}",
                "{\"type\":\"error\",\"error\":\"Cast error mid-scan\",\"error_code\":\"runtime\"}") + "\n";
        server.enqueue(ndjson(body));

        try (DatalatheStreamingResultSet rs = client.generateReportStream(
                Arrays.asList("chip1"), "SELECT id FROM t")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));

            SQLException ex = assertThrows(SQLException.class, rs::next);
            Throwable cause = ex.getCause();
            assertTrue(cause instanceof DatalatheQueryException,
                    "error frame must wrap DatalatheQueryException");
            assertTrue(ex.getMessage().contains("Cast error mid-scan"));
        }
    }

    @Test
    void truncatedStreamThrows() throws Exception {
        // Schema + rows but no terminal end/error frame: transport failure.
        String body = String.join("\n",
                "{\"type\":\"schema\",\"schema\":[{\"name\":\"id\",\"data_type\":\"Int32\"}]}",
                "{\"type\":\"rows\",\"rows\":[[\"1\"]]}") + "\n";
        server.enqueue(ndjson(body));

        try (DatalatheStreamingResultSet rs = client.generateReportStream(
                Arrays.asList("chip1"), "SELECT id FROM t")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));

            SQLException ex = assertThrows(SQLException.class, rs::next);
            assertTrue(ex.getMessage().toLowerCase().contains("terminal"),
                    "truncated stream message should mention the missing terminal frame");
        }
    }

    @Test
    void emptyResult() throws Exception {
        String body = String.join("\n",
                "{\"type\":\"schema\",\"schema\":[{\"name\":\"id\",\"data_type\":\"Int32\"}]}",
                "{\"type\":\"end\",\"row_count\":0}") + "\n";
        server.enqueue(ndjson(body));

        try (DatalatheStreamingResultSet rs = client.generateReportStream(
                Arrays.asList("chip1"), "SELECT id FROM t WHERE 1=0")) {
            assertEquals(1, rs.getMetaData().getColumnCount());
            assertFalse(rs.next());
            assertEquals(0L, rs.getRowCount());
        }
    }

    @Test
    void wasNullOnNullCells() throws Exception {
        String body = String.join("\n",
                "{\"type\":\"schema\",\"schema\":[{\"name\":\"id\",\"data_type\":\"Int32\"},"
                        + "{\"name\":\"name\",\"data_type\":\"Utf8\"}]}",
                "{\"type\":\"rows\",\"rows\":[[\"1\",null],[null,\"bob\"]]}",
                "{\"type\":\"end\",\"row_count\":2}") + "\n";
        server.enqueue(ndjson(body));

        try (DatalatheStreamingResultSet rs = client.generateReportStream(
                Arrays.asList("chip1"), "SELECT id, name FROM t")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertFalse(rs.wasNull());
            assertNull(rs.getString(2));
            assertTrue(rs.wasNull());

            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
            assertTrue(rs.wasNull());
            assertEquals("bob", rs.getString(2));
            assertFalse(rs.wasNull());

            assertFalse(rs.next());
        }
    }

    @Test
    void requestErrorBeforeStreamThrowsChipNotFound() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Chip 'lost' is not available\","
                        + "\"error_code\":\"chip_not_found\",\"chip_id\":\"lost\"}"));

        ChipNotFoundException ex = assertThrows(ChipNotFoundException.class,
                () -> client.generateReportStream(Arrays.asList("lost"), "SELECT 1"));
        assertEquals("lost", ex.getChipId());
    }

    @Test
    void transformedQueryExposedFromSchemaFrame() throws Exception {
        String body = String.join("\n",
                "{\"type\":\"schema\",\"schema\":[{\"name\":\"n\",\"data_type\":\"Utf8\"}],"
                        + "\"transformed_query\":\"SELECT COALESCE(n,'x') FROM t\"}",
                "{\"type\":\"rows\",\"rows\":[[\"a\"]]}",
                "{\"type\":\"end\",\"row_count\":1}") + "\n";
        server.enqueue(ndjson(body));

        try (DatalatheStreamingResultSet rs = client.generateReportStream(
                Arrays.asList("chip1"), "SELECT IFNULL(n,'x') FROM t", true, true)) {
            assertEquals("SELECT COALESCE(n,'x') FROM t", rs.getTransformedQuery());
            assertTrue(rs.next());
            assertEquals("a", rs.getString(1));
            assertFalse(rs.next());
        }
    }
}
