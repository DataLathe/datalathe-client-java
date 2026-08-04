package com.datalathe.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies automatic retry of HTTP 429 responses: retries honor
 * {@code Retry-After}, fall back to exponential backoff without it,
 * exhaust after 3 retries with the same exception surface as before,
 * can be disabled, and never fire for non-429 statuses.
 */
class RetryOn429Test {

    private static final String CHIPS_BODY = "{\"chips\":[],\"metadata\":[],\"tags\":[]}";
    private static final String SATURATED_BODY =
            "{\"error_code\":\"admission_saturated\",\"message\":\"engine saturated\"}";

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    private String baseUrl() {
        String u = server.url("/").toString();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private void enqueue429(String retryAfter) {
        MockResponse response = new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody(SATURATED_BODY);
        if (retryAfter != null) {
            response.setHeader("Retry-After", retryAfter);
        }
        server.enqueue(response);
    }

    private void enqueueChips() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(CHIPS_BODY));
    }

    @Test
    void retriesPostOn429AndReplaysBody() throws Exception {
        enqueue429("0");
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"tables\":[\"t1\"]}"));
        DatalatheClient client = new DatalatheClient(baseUrl());

        List<String> tables = client.extractTables("SELECT * FROM t1");

        assertEquals(List.of("t1"), tables);
        assertEquals(2, server.getRequestCount());
        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertEquals(first.getBody().readUtf8(), second.getBody().readUtf8(),
                "the retried POST must replay the original request body");
    }

    @Test
    void retriesWithExponentialBackoffWhenRetryAfterMissing() throws Exception {
        enqueue429(null);
        enqueue429(null);
        enqueueChips();
        DatalatheClient client = new DatalatheClient(baseUrl(), Map.of(),
                RetryConfig.builder().backoffBaseMillis(1).build());

        client.listChips();

        assertEquals(3, server.getRequestCount());
    }

    @Test
    void retriesWhenRetryAfterUnparseable() throws Exception {
        enqueue429("soon");
        enqueueChips();
        DatalatheClient client = new DatalatheClient(baseUrl(), Map.of(),
                RetryConfig.builder().backoffBaseMillis(1).build());

        client.listChips();

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void exhaustsRetriesAndSurfacesNormal429Failure() {
        for (int i = 0; i < 4; i++) {
            enqueue429("0");
        }
        DatalatheClient client = new DatalatheClient(baseUrl());

        DatalatheApiException e = assertThrows(DatalatheApiException.class, () -> client.listChips());

        assertEquals(429, e.getStatusCode());
        assertEquals("admission_saturated", e.getErrorCode());
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void disabledRetriesSurface429OnFirstResponse() {
        enqueue429("0");
        DatalatheClient client = new DatalatheClient(baseUrl(), Map.of(), RetryConfig.DISABLED);

        DatalatheApiException e = assertThrows(DatalatheApiException.class, () -> client.listChips());

        assertEquals(429, e.getStatusCode());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void doesNotRetryOn500() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("internal error"));
        DatalatheClient client = new DatalatheClient(baseUrl());

        IOException e = assertThrows(IOException.class, () -> client.listChips());

        assertTrue(e.getMessage().contains("500"));
        assertEquals(1, server.getRequestCount());
    }
}
