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

/**
 * Verifies automatic retry of HTTP 503 responses: a 503 is retried only
 * when its {@code Retry-After} header is a whole number of seconds from 0
 * to 30, shares the retry budget with 429, and is otherwise returned to
 * the caller on the first response.
 */
class RetryOn503Test {

    private static final String CHIPS_BODY = "{\"chips\":[],\"metadata\":[],\"tags\":[]}";
    private static final String SHUTTING_DOWN_BODY =
            "{\"error_code\":\"SHUTTING_DOWN\",\"message\":\"engine is shutting down\"}";
    private static final String LICENSE_BODY =
            "{\"error_code\":\"LICENSE_UNAVAILABLE\",\"message\":\"license lease unavailable\"}";
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

    private void enqueue(int code, String body, String retryAfter) {
        MockResponse response = new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
        if (retryAfter != null) {
            response.setHeader("Retry-After", retryAfter);
        }
        server.enqueue(response);
    }

    private void enqueue503(String retryAfter) {
        enqueue(503, SHUTTING_DOWN_BODY, retryAfter);
    }

    private DatalatheClient clientWithoutWait() {
        return new DatalatheClient(baseUrl(), Map.of(),
                RetryConfig.builder().maxWaitMillis(1).build());
    }

    private void enqueueChips() {
        enqueue(200, CHIPS_BODY, null);
    }

    private void assertNotRetried(DatalatheClient client, String expectedErrorCode) {
        DatalatheApiException e = assertThrows(DatalatheApiException.class, () -> client.listChips());

        assertEquals(503, e.getStatusCode());
        assertEquals(expectedErrorCode, e.getErrorCode());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void retriesOn503WithShortRetryAfter() throws Exception {
        enqueue503("1");
        enqueueChips();
        DatalatheClient client = new DatalatheClient(baseUrl());

        client.listChips();

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void retriesPostOn503AndReplaysBody() throws Exception {
        enqueue503("1");
        enqueue(200, "{\"tables\":[\"t1\"]}", null);
        DatalatheClient client = clientWithoutWait();

        List<String> tables = client.extractTables("SELECT * FROM t1");

        assertEquals(List.of("t1"), tables);
        assertEquals(2, server.getRequestCount());
        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertEquals(first.getBody().readUtf8(), second.getBody().readUtf8());
    }

    @Test
    void retriesOn503WhenRetryAfterEqualsCap() throws Exception {
        enqueue503("30");
        enqueueChips();
        DatalatheClient client = clientWithoutWait();

        client.listChips();

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void doesNotRetryOn503WhenRetryAfterExceedsCap() {
        enqueue503("31");
        DatalatheClient client = clientWithoutWait();

        assertNotRetried(client, "SHUTTING_DOWN");
    }

    @Test
    void doesNotRetryOn503WhenRaisedMaxWaitCoversRetryAfter() {
        enqueue(503, LICENSE_BODY, "60");
        DatalatheClient client = new DatalatheClient(baseUrl(), Map.of(),
                RetryConfig.builder().maxWaitMillis(120_000).build());

        assertNotRetried(client, "LICENSE_UNAVAILABLE");
    }

    @Test
    void doesNotRetryOn503WithLicenseRetryAfter() {
        enqueue(503, LICENSE_BODY, "60");
        DatalatheClient client = new DatalatheClient(baseUrl());

        assertNotRetried(client, "LICENSE_UNAVAILABLE");
    }

    @Test
    void doesNotRetryOn503WithoutRetryAfter() {
        enqueue503(null);
        DatalatheClient client = new DatalatheClient(baseUrl());

        assertNotRetried(client, "SHUTTING_DOWN");
    }

    @Test
    void doesNotRetryOn503WhenRetryAfterUnparseable() {
        enqueue503("soon");
        DatalatheClient client = new DatalatheClient(baseUrl());

        assertNotRetried(client, "SHUTTING_DOWN");
    }

    @Test
    void doesNotRetryOn503WhenRetryAfterIsHttpDate() {
        enqueue503("Wed, 11 Nov 2026 07:28:00 GMT");
        DatalatheClient client = new DatalatheClient(baseUrl());

        assertNotRetried(client, "SHUTTING_DOWN");
    }

    @Test
    void doesNotRetryOn503WhenRetryAfterNegative() {
        enqueue503("-1");
        DatalatheClient client = new DatalatheClient(baseUrl());

        assertNotRetried(client, "SHUTTING_DOWN");
    }

    @Test
    void disabledRetriesSurface503OnFirstResponse() {
        enqueue503("1");
        DatalatheClient client = new DatalatheClient(baseUrl(), Map.of(), RetryConfig.DISABLED);

        assertNotRetried(client, "SHUTTING_DOWN");
    }

    @Test
    void exhaustsRetriesAndSurfacesLast503() {
        for (int i = 0; i < 4; i++) {
            enqueue503("1");
        }
        DatalatheClient client = clientWithoutWait();

        DatalatheApiException e = assertThrows(DatalatheApiException.class, () -> client.listChips());

        assertEquals(503, e.getStatusCode());
        assertEquals("SHUTTING_DOWN", e.getErrorCode());
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void sharesRetryBudgetBetween429And503() {
        enqueue(429, SATURATED_BODY, "0");
        enqueue503("1");
        enqueue(429, SATURATED_BODY, "0");
        enqueue503("1");
        enqueueChips();
        DatalatheClient client = clientWithoutWait();

        DatalatheApiException e = assertThrows(DatalatheApiException.class, () -> client.listChips());

        assertEquals(503, e.getStatusCode());
        assertEquals(4, server.getRequestCount());
    }
}
