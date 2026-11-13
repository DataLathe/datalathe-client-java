package com.datalathe.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogTimeoutTest {

    private static final String CHIPS_BODY = "{\"chips\":[],\"metadata\":[],\"tags\":[]}";
    private static final Duration SHORT = Duration.ofMillis(300);

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

    private DatalatheClient client(Duration catalogTimeout) {
        String u = server.url("/").toString();
        return new DatalatheClient(u.endsWith("/") ? u.substring(0, u.length() - 1) : u,
                Collections.emptyMap(), RetryConfig.DEFAULT, catalogTimeout);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse silent() {
        return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
    }

    @Test
    void searchIsRetriedOnceAfterNoResponse() throws Exception {
        server.enqueue(silent());
        server.enqueue(json(CHIPS_BODY));

        long start = System.nanoTime();
        assertNotNull(client(SHORT).searchChips("loan02", null, "tenant", "380"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(2, server.getRequestCount());
        assertTrue(elapsedMillis < 5_000, "took " + elapsedMillis + " ms");
    }

    @Test
    void secondSilenceIsReportedAsATimeout() {
        server.enqueue(silent());
        server.enqueue(silent());

        assertThrows(SocketTimeoutException.class, () -> client(SHORT).getChip("c1"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void tagWriteIsRetriedOnceAfterNoResponse() throws Exception {
        server.enqueue(silent());
        server.enqueue(json("{}"));

        client(SHORT).addChipTags("c1", Map.of("loan_portfolio_id", "380"));

        assertEquals(2, server.getRequestCount());
        assertEquals("POST", server.takeRequest().getMethod());
        assertEquals("/lathe/chips/c1/tags", server.takeRequest().getPath());
    }

    @Test
    void ingestJobStatusUsesTheShortTimeout() throws Exception {
        server.enqueue(silent());
        server.enqueue(json("{\"job_id\":\"j1\",\"status\":\"running\"}"));

        assertNotNull(client(SHORT).getIngestJob("j1"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void aSlowReportIsNotCutShort() throws Exception {
        server.enqueue(json("{\"result\":{}}").setHeadersDelay(900, TimeUnit.MILLISECONDS));

        client(SHORT).generateReport(java.util.List.of("c1"), java.util.List.of("SELECT 1"));

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void aSlowStagingIsNotCutShortOrRepeated() throws Exception {
        server.enqueue(json("{\"chip_id\":\"c1\"}").setHeadersDelay(900, TimeUnit.MILLISECONDS));

        assertEquals("c1", client(SHORT).createChip("database", "SELECT 1", "t"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void aChipDeleteIsNotRepeated() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204).setHeadersDelay(900, TimeUnit.MILLISECONDS));

        client(SHORT).deleteChip("c1");

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void nullTimeoutLeavesCatalogCallsOnTheLongTimeout() throws Exception {
        server.enqueue(json(CHIPS_BODY).setHeadersDelay(900, TimeUnit.MILLISECONDS));

        assertNotNull(client(null).searchChips(null, null, null, null));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void aCorrelationCopyKeepsTheShortTimeout() throws Exception {
        server.enqueue(silent());
        server.enqueue(json(CHIPS_BODY));

        assertNotNull(client(SHORT).withCorrelationId("run-1").searchChips(null, null, null, null));

        assertEquals(2, server.getRequestCount());
        assertEquals("run-1", server.takeRequest().getHeader(DatalatheClient.CORRELATION_ID_HEADER));
    }

    @Test
    void theDefaultIsFiveSeconds() {
        assertEquals(Duration.ofSeconds(5), DatalatheClient.DEFAULT_CATALOG_TIMEOUT);
    }
}
