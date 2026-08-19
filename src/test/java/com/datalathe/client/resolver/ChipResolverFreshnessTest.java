package com.datalathe.client.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChipResolverFreshnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;
    private DatalatheClient client;
    private ExecutorService executor;
    private ChipResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
        executor = Executors.newFixedThreadPool(2);
        resolver = new ChipResolver(client, executor, 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.shutdown();
    }

    private static ChipFactory factory(boolean partitioned, Map<String, String> freshness) {
        return new ChipFactory() {
            @Override
            public boolean isPartitioned(String table) {
                return partitioned;
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

            @Override
            public Map<String, String> freshnessTags(String table) {
                return freshness;
            }
        };
    }

    private void enqueueJson(int code, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private void enqueueSearch(String chipsJson, String tagsJson) {
        enqueueJson(200, "{\"chips\":[" + chipsJson + "],\"metadata\":[],\"tags\":[" + tagsJson + "]}");
    }

    private static String chip(String id, String table, String partitionValue) {
        String pv = partitionValue == null ? "null" : "\"" + partitionValue + "\"";
        return "{\"chip_id\":\"" + id + "\",\"sub_chip_id\":\"" + id + "\","
                + "\"table_name\":\"" + table + "\",\"partition_value\":" + pv + "}";
    }

    private static String tag(String chipId, String key, String value) {
        return "{\"chip_id\":\"" + chipId + "\",\"key\":\"" + key + "\",\"value\":\"" + value + "\"}";
    }

    private List<RecordedRequest> drainRequests() throws Exception {
        List<RecordedRequest> requests = new ArrayList<>();
        for (int i = 0; i < server.getRequestCount(); i++) {
            requests.add(server.takeRequest());
        }
        return requests;
    }

    @Test
    void staleChipIsEvictedAndRecreatedInOnePass() throws Exception {
        enqueueSearch(chip("c1", "users", null),
                tag("c1", "tenant", "42") + "," + tag("c1", "schema_version", "v1"));
        enqueueJson(200, "{}");
        enqueueJson(200, "{\"chip_id\":\"c2\"}");

        ResolvedChips resolved = resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42", factory(false, Map.of("schema_version", "v2")));

        assertEquals(List.of("c2"), resolved.allChipIds());
        List<RecordedRequest> requests = drainRequests();
        assertEquals(3, requests.size());
        assertEquals("DELETE", requests.get(1).getMethod());
        assertTrue(requests.get(1).getPath().endsWith("/lathe/chips/c1"));
        assertEquals("POST", requests.get(2).getMethod());
    }

    @Test
    void matchingFreshnessTagsKeepChip() throws Exception {
        enqueueSearch(chip("c1", "users", null),
                tag("c1", "tenant", "42") + "," + tag("c1", "schema_version", "v2"));

        ResolvedChips resolved = resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42", factory(false, Map.of("schema_version", "v2")));

        assertEquals(List.of("c1"), resolved.allChipIds());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void untaggedChipWithDeclaredFreshnessIsEvicted() throws Exception {
        enqueueSearch(chip("c1", "users", null), tag("c1", "tenant", "42"));
        enqueueJson(200, "{}");
        enqueueJson(200, "{\"chip_id\":\"c2\"}");

        ResolvedChips resolved = resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42", factory(false, Map.of("schema_version", "v2")));

        assertEquals(List.of("c2"), resolved.allChipIds());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void nullFreshnessTagsLeaveChipsAlone() throws Exception {
        enqueueSearch(chip("c1", "users", null), tag("c1", "tenant", "42"));

        ResolvedChips resolved = resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42", factory(false, null));

        assertEquals(List.of("c1"), resolved.allChipIds());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void anyDifferingTagAmongSeveralEvicts() throws Exception {
        enqueueSearch(chip("c1", "users", null),
                tag("c1", "tenant", "42") + ","
                        + tag("c1", "schema_version", "v2") + ","
                        + tag("c1", "latest_max_date", "2026-07-31"));
        enqueueJson(200, "{}");
        enqueueJson(200, "{\"chip_id\":\"c2\"}");

        ResolvedChips resolved = resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42",
                factory(false, Map.of("schema_version", "v2", "latest_max_date", "2026-08-19")));

        assertEquals(List.of("c2"), resolved.allChipIds());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void createdChipsCarryFreshnessTags() throws Exception {
        enqueueSearch("", "");
        enqueueJson(200, "{\"chip_id\":\"c1\"}");

        resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42", factory(false, Map.of("schema_version", "v2")));

        List<RecordedRequest> requests = drainRequests();
        assertEquals(2, requests.size());
        JsonNode body = MAPPER.readTree(requests.get(1).getBody().readUtf8());
        assertEquals("42", body.get("tags").get("tenant").asText());
        assertEquals("v2", body.get("tags").get("schema_version").asText());
    }

    @Test
    void concurrentEvictDelete404StillRecreates() throws Exception {
        enqueueSearch(chip("c1", "users", null),
                tag("c1", "tenant", "42") + "," + tag("c1", "schema_version", "v1"));
        enqueueJson(404, "{\"error_code\":\"chip_not_found\",\"chip_id\":\"c1\",\"error\":\"gone\"}");
        enqueueJson(200, "{\"chip_id\":\"c2\"}");

        ResolvedChips resolved = resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42", factory(false, Map.of("schema_version", "v2")));

        assertEquals(List.of("c2"), resolved.allChipIds());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void deleteFailureKeepsStaleChip() throws Exception {
        enqueueSearch(chip("c1", "users", null),
                tag("c1", "tenant", "42") + "," + tag("c1", "schema_version", "v1"));
        enqueueJson(500, "{\"error_code\":\"INTERNAL\",\"message\":\"boom\"}");

        ResolvedChips resolved = resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42", factory(false, Map.of("schema_version", "v2")));

        assertEquals(List.of("c1"), resolved.allChipIds());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void onlyStalePartitionIsRecreated() throws Exception {
        enqueueSearch(chip("c1", "loans", "2026-01") + "," + chip("c2", "loans", "2026-02"),
                tag("c1", "tenant", "42") + "," + tag("c1", "schema_version", "v1") + ","
                        + tag("c2", "tenant", "42") + "," + tag("c2", "schema_version", "v2"));
        enqueueJson(200, "{}");
        enqueueJson(200, "{\"chip_id\":\"c3\"}");

        ResolvedChips resolved = resolver.resolveForTables(Set.of("loans"),
                List.of("2026-01", "2026-02"),
                "tenant", "42", factory(true, Map.of("schema_version", "v2")));

        assertEquals(Set.of("c2", "c3"), Set.copyOf(resolved.allChipIds()));
        List<RecordedRequest> requests = drainRequests();
        assertEquals(3, requests.size());
        assertEquals("DELETE", requests.get(1).getMethod());
        assertTrue(requests.get(1).getPath().endsWith("/lathe/chips/c1"));
    }

    @Test
    void freshnessKeyCollidingWithTenantTagKeyThrows() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                resolver.resolveForTables(Set.of("users"), List.of(),
                        "tenant", "42", factory(false, Map.of("tenant", "43"))));
        assertEquals(0, server.getRequestCount());
    }
}
