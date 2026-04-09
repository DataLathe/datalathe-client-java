package com.datalathe.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that {@link DatalatheClient}'s default-headers feature
 * correctly injects caller-supplied headers on every outbound HTTP
 * request, and that the single-arg constructor remains header-free
 * for backward compatibility.
 */
class DatalatheClientHeadersTest {

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

    /**
     * Enqueues a canned empty SearchChipsResponse JSON body on the mock
     * server. The client will deserialize this successfully; we only
     * care about the recorded outbound request, not the response.
     */
    private void enqueueEmptySearchResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chips\":[],\"metadata\":[],\"tags\":[]}"));
    }

    private String baseUrl() {
        // MockWebServer's url("/") returns something like "http://127.0.0.1:54321/"
        // The client appends paths directly, so we strip the trailing slash.
        String u = server.url("/").toString();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    @Test
    void noDefaultHeaders_usesSingleArgConstructor_sendsNoDevKey() throws Exception {
        enqueueEmptySearchResponse();
        DatalatheClient client = new DatalatheClient(baseUrl());

        client.searchChips("loan02", null, null, null);

        RecordedRequest recorded = server.takeRequest();
        assertNull(
                recorded.getHeader("x-datalathe-dev-key"),
                "single-arg constructor must not add any default headers");
    }

    @Test
    void singleDefaultHeader_addsToOutboundRequest() throws Exception {
        enqueueEmptySearchResponse();
        DatalatheClient client = new DatalatheClient(
                baseUrl(),
                Map.of("x-datalathe-dev-key", "test-key"));

        client.searchChips("loan02", null, null, null);

        RecordedRequest recorded = server.takeRequest();
        assertEquals(
                "test-key",
                recorded.getHeader("x-datalathe-dev-key"),
                "the dev-key header should appear on outbound requests when the "
                + "two-arg constructor is used");
    }

    @Test
    void multipleDefaultHeaders_allAppearOnOutboundRequest() throws Exception {
        enqueueEmptySearchResponse();
        DatalatheClient client = new DatalatheClient(
                baseUrl(),
                Map.of(
                        "x-datalathe-dev-key", "k",
                        "x-custom-header", "c"));

        client.searchChips("loan02", null, null, null);

        RecordedRequest recorded = server.takeRequest();
        assertEquals("k", recorded.getHeader("x-datalathe-dev-key"));
        assertEquals("c", recorded.getHeader("x-custom-header"));
    }
}
