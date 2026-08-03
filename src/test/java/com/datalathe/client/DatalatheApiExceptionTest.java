package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatalatheApiExceptionTest {

    private MockWebServer server;
    private DatalatheClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private ChipSource source() {
        return ChipSource.builder()
                .sourceType(SourceType.MYSQL)
                .databaseName("db")
                .tableName("users")
                .query("SELECT * FROM users")
                .build();
    }

    @Test
    void structuredErrorBodyThrowsTypedException() {
        String body = "{\"error_code\":\"EMPTY_SOURCE\","
                + "\"message\":\"No partitions to register\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(body));

        DatalatheApiException e = assertThrows(DatalatheApiException.class,
                () -> client.createChip(source()));

        assertEquals(500, e.getStatusCode());
        assertEquals("EMPTY_SOURCE", e.getErrorCode());
        assertEquals("No partitions to register", e.getServerMessage());
        assertEquals("POST /lathe/stage/data failed: 500 " + body, e.getMessage());
    }

    @Test
    void structuredErrorWithoutCodeStillTyped() {
        String body = "{\"message\":\"boom\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody(body));

        DatalatheApiException e = assertThrows(DatalatheApiException.class,
                () -> client.createChip(source()));

        assertEquals(503, e.getStatusCode());
        assertNull(e.getErrorCode());
        assertEquals("boom", e.getServerMessage());
        assertEquals("POST /lathe/stage/data failed: 503 " + body, e.getMessage());
    }

    @Test
    void nonJsonBodyFallsBackToPlainIOException() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        IOException e = assertThrows(IOException.class, () -> client.createChip(source()));

        assertFalse(e instanceof DatalatheApiException);
        assertEquals("POST /lathe/stage/data failed: 500 Internal Server Error", e.getMessage());
    }

    @Test
    void jsonBodyWithoutErrorFieldsFallsBackToPlainIOException() {
        String body = "{\"error\":\"something else\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(body));

        IOException e = assertThrows(IOException.class, () -> client.createChip(source()));

        assertFalse(e instanceof DatalatheApiException);
        assertEquals("POST /lathe/stage/data failed: 500 " + body, e.getMessage());
    }

    @Test
    void chipNotFoundStillThrownAndIsApiException() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Chip 'abc123' is not available (may have expired)\","
                        + "\"error_code\":\"chip_not_found\",\"chip_id\":\"abc123\"}"));

        ChipNotFoundException e = assertThrows(ChipNotFoundException.class,
                () -> client.generateReport(List.of("abc123"), List.of("SELECT 1")));

        assertTrue(e instanceof DatalatheApiException);
        assertEquals("abc123", e.getChipId());
        assertEquals(404, e.getStatusCode());
        assertEquals("chip_not_found", e.getErrorCode());
        assertEquals("Chip 'abc123' is not available (may have expired)", e.getMessage());
        assertEquals("Chip 'abc123' is not available (may have expired)", e.getServerMessage());
    }

    @Test
    void failIfEmptyOmittedWhenUnset() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"chip_id\":\"c1\"}"));

        client.createChip(source());

        RecordedRequest request = server.takeRequest();
        assertFalse(request.getBody().readUtf8().contains("fail_if_empty"));
    }

    @Test
    void failIfEmptySerializedWhenSet() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"chip_id\":\"c1\"}"));

        ChipSource source = source();
        source.setFailIfEmpty(true);
        client.createChip(source);

        RecordedRequest request = server.takeRequest();
        assertTrue(request.getBody().readUtf8().contains("\"fail_if_empty\":true"));
    }
}
