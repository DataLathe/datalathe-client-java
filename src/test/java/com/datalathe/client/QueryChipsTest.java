package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.types.ChipQueryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryChipsTest {

    private MockWebServer server;
    private DatalatheClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void enqueueJson(int code, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    @Test
    void queryChipsPostsWireBodyAndParsesResult() throws Exception {
        enqueueJson(200, "{\"columns\":[{\"name\":\"n\",\"data_type\":\"BigInt\"}],"
                + "\"rows\":[[\"3\"]],\"truncated\":false}");

        ChipQueryResult result = client.queryChips(List.of("chip-1"),
                "SELECT COUNT(*) AS n FROM s_chip_1.main.loans");

        RecordedRequest request = server.takeRequest();
        assertEquals("/lathe/chips/query", request.getPath());
        assertEquals("POST", request.getMethod());
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertEquals("chip-1", body.get("chip_ids").get(0).asText());
        assertEquals(1, body.get("chip_ids").size());
        assertEquals("SELECT COUNT(*) AS n FROM s_chip_1.main.loans", body.get("query").asText());

        assertEquals(1, result.getColumns().size());
        assertEquals("n", result.getColumns().get(0).getName());
        assertEquals("BigInt", result.getColumns().get(0).getDataType());
        assertEquals(List.of(List.of("3")), result.getRows());
        assertFalse(result.isTruncated());
    }

    @Test
    void queryChipsParsesNullCellsAndTruncatedFlag() throws Exception {
        enqueueJson(200, "{\"columns\":[{\"name\":\"region\",\"data_type\":\"Utf8\"}],"
                + "\"rows\":[[null],[\"LATAM\"]],\"truncated\":true}");

        ChipQueryResult result = client.queryChips(List.of("chip-1"), "SELECT region FROM t");

        server.takeRequest();
        assertEquals(2, result.getRows().size());
        assertEquals(null, result.getRows().get(0).get(0));
        assertEquals("LATAM", result.getRows().get(1).get(0));
        assertTrue(result.isTruncated());
    }

    @Test
    void queryChipsThrowsChipNotFound() {
        enqueueJson(404, "{\"error\":\"Chip 'ghost' is not available (may have expired)\","
                + "\"error_code\":\"chip_not_found\",\"chip_id\":\"ghost\"}");

        ChipNotFoundException e = assertThrows(ChipNotFoundException.class,
                () -> client.queryChips(List.of("ghost"), "select 1"));

        assertEquals("ghost", e.getChipId());
    }

    @Test
    void queryChipsThrowsApiExceptionOnQueryError() {
        enqueueJson(400, "{\"error_code\":\"QUERY_ERROR\","
                + "\"message\":\"Binder Error: column x not found\"}");

        DatalatheApiException e = assertThrows(DatalatheApiException.class,
                () -> client.queryChips(List.of("chip-1"), "SELECT x FROM t"));

        assertEquals(400, e.getStatusCode());
        assertEquals("QUERY_ERROR", e.getErrorCode());
        assertEquals("Binder Error: column x not found", e.getServerMessage());
    }
}
