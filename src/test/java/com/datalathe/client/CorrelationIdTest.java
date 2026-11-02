package com.datalathe.client;

import com.datalathe.client.types.IngestJob;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private String baseUrl() {
        String u = server.url("/").toString();
        return u.substring(0, u.length() - 1);
    }

    private void enqueueEmptySearch() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chips\":[],\"metadata\":[],\"tags\":[]}"));
    }

    @Test
    void copySendsHeaderAndOriginalDoesNot() throws Exception {
        DatalatheClient base = new DatalatheClient(baseUrl());
        DatalatheClient scoped = base.withCorrelationId("03a813fe-6a62-53fb-8144-846c197ac8f6");

        enqueueEmptySearch();
        scoped.searchChips("t", null, null, null);
        enqueueEmptySearch();
        base.searchChips("t", null, null, null);

        assertEquals("03a813fe-6a62-53fb-8144-846c197ac8f6",
                server.takeRequest().getHeader("X-Correlation-ID"));
        assertNull(server.takeRequest().getHeader("X-Correlation-ID"));
    }

    @Test
    void copySharesConnectionPool() {
        DatalatheClient base = new DatalatheClient(baseUrl());
        DatalatheClient scoped = base.withCorrelationId("nc-1");
        assertNotSame(base, scoped);
        assertSame(base.httpClient().connectionPool(), scoped.httpClient().connectionPool());
        assertSame(base.httpClient().dispatcher(), scoped.httpClient().dispatcher());
    }

    @Test
    void blankOrNullReturnsSameClient() {
        DatalatheClient base = new DatalatheClient(baseUrl());
        assertSame(base, base.withCorrelationId(null));
        assertSame(base, base.withCorrelationId(""));
        assertSame(base, base.withCorrelationId("   "));
    }

    @Test
    void invalidIdWarnsAndSendsNothing() throws Exception {
        DatalatheClient base = new DatalatheClient(baseUrl());
        DatalatheClient scoped = assertDoesNotThrow(() -> base.withCorrelationId("bad id\n"));
        assertSame(base, scoped);
        enqueueEmptySearch();
        scoped.searchChips("t", null, null, null);
        assertNull(server.takeRequest().getHeader("X-Correlation-ID"));
    }

    @Test
    void chainedCopyUsesLatestId() throws Exception {
        DatalatheClient scoped = new DatalatheClient(baseUrl())
                .withCorrelationId("first").withCorrelationId("second");
        enqueueEmptySearch();
        scoped.searchChips("t", null, null, null);
        RecordedRequest r = server.takeRequest();
        assertEquals("second", r.getHeader("X-Correlation-ID"));
        assertEquals(1, r.getHeaders().values("X-Correlation-ID").size());
    }

    @Test
    void retriedRequestKeepsHeader() throws Exception {
        DatalatheClient scoped = new DatalatheClient(baseUrl()).withCorrelationId("nc-retry");
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "0"));
        enqueueEmptySearch();
        scoped.searchChips("t", null, null, null);
        assertEquals("nc-retry", server.takeRequest().getHeader("X-Correlation-ID"));
        assertEquals("nc-retry", server.takeRequest().getHeader("X-Correlation-ID"));
    }

    @Test
    void ingestJobExposesCorrelationIdAndToleratesAbsence() throws Exception {
        DatalatheClient client = new DatalatheClient(baseUrl());
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"job_id\":\"j1\",\"status\":\"queued\",\"correlation_id\":\"nc-job\"}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"job_id\":\"j2\",\"status\":\"queued\"}"));
        IngestJob withId = client.getIngestJob("j1");
        IngestJob without = client.getIngestJob("j2");
        assertEquals("nc-job", withId.getCorrelationId());
        assertNull(without.getCorrelationId());
    }
}
