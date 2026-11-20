package com.datalathe.client.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChipResolverAdditionalTagsTest {

    private MockWebServer server;
    private ScriptedEngine engine;
    private ExecutorService executor;
    private ChipResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        engine = new ScriptedEngine();
        server.setDispatcher(engine);
        server.start();
        DatalatheClient client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
        executor = Executors.newFixedThreadPool(4);
        resolver = new ChipResolver(client, executor, 1, 30);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.shutdown();
    }

    private abstract static class BaseFactory implements ChipFactory {
        @Override
        public boolean isPartitioned(String table) {
            return "orders".equals(table);
        }

        @Override
        public ChipSource buildSource(String table, String partitionValue) {
            return ChipSource.builder()
                    .sourceType(SourceType.MYSQL)
                    .databaseName("db")
                    .tableName(table)
                    .query("SELECT * FROM " + table)
                    .partition(partitionValue == null ? null : ChipSource.Partition.builder()
                            .partitionBy("month")
                            .partitionValues(List.of(partitionValue))
                            .build())
                    .build();
        }
    }

    private static Map<String, String> tagsOf(JsonNode createBody) {
        Map<String, String> tags = new HashMap<>();
        createBody.get("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        return tags;
    }

    @Test
    void additionalTagsGoInTheCreateRequest() throws Exception {
        engine.create("users", "c1");
        ChipFactory factory = new BaseFactory() {
            @Override
            public Map<String, String> freshnessTags(String table) {
                return Map.of("schema_version", "v2");
            }

            @Override
            public Map<String, String> additionalTags(String table, String partitionValue) {
                return Map.of("region", "emea", "owner", "reports");
            }
        };

        ResolvedChips chips = resolver.resolveForTables(Set.of("users"), List.of(), "tenant", "42", factory);

        assertEquals(List.of("c1"), chips.allChipIds());
        assertEquals(1, engine.creates.size());
        assertEquals(Map.of("tenant", "42", "schema_version", "v2", "region", "emea", "owner", "reports"),
                tagsOf(engine.creates.get(0)));
        assertEquals(1, engine.searches.size());
        assertTrue(engine.others.isEmpty(), "no follow-up tag call: " + engine.others);
    }

    @Test
    void partitionedChipsCarryTheirOwnPartitionsTags() throws Exception {
        engine.create("orders|2026-01", "c1");
        engine.create("orders|2026-02", "c2");
        ChipFactory factory = new BaseFactory() {
            @Override
            public Map<String, String> additionalTags(String table, String partitionValue) {
                return Map.of("period", partitionValue);
            }
        };

        resolver.resolveForTables(Set.of("orders"), List.of("2026-01", "2026-02"), "tenant", "42", factory);

        assertEquals(2, engine.creates.size());
        for (JsonNode create : engine.creates) {
            String pv = create.get("source_request").get("partition").get("partition_values").get(0).asText();
            assertEquals(Map.of("tenant", "42", "period", pv), tagsOf(create));
        }
    }

    @Test
    void factoryWithoutAdditionalTagsSendsOnlyTheTenantTag() throws Exception {
        engine.create("users", "c1");

        resolver.resolveForTables(Set.of("users"), List.of(), "tenant", "42", new BaseFactory() {});

        assertEquals(Map.of("tenant", "42"), tagsOf(engine.creates.get(0)));
    }

    @Test
    void differentAdditionalTagsOnAFoundChipDoNotEvictIt() throws Exception {
        engine.search(ScriptedEngine.found(ScriptedEngine.chip("c1", "users", null),
                ScriptedEngine.tag("c1", "tenant", "42") + "," + ScriptedEngine.tag("c1", "region", "apac")));
        ChipFactory factory = new BaseFactory() {
            @Override
            public Map<String, String> additionalTags(String table, String partitionValue) {
                return Map.of("region", "emea");
            }
        };

        ResolvedChips chips = resolver.resolveForTables(Set.of("users"), List.of(), "tenant", "42", factory);

        assertEquals(List.of("c1"), chips.allChipIds());
        assertTrue(engine.deletes.isEmpty());
        assertTrue(engine.creates.isEmpty());
    }

    @Test
    void additionalTagKeyCollidingWithTenantTagKeyThrows() {
        ChipFactory factory = new BaseFactory() {
            @Override
            public Map<String, String> additionalTags(String table, String partitionValue) {
                return Map.of("tenant", "43");
            }
        };

        assertThrows(IllegalArgumentException.class, () ->
                resolver.resolveForTables(Set.of("users"), List.of(), "tenant", "42", factory));
        assertEquals(0, engine.searches.size());
    }

    @Test
    void additionalTagKeyCollidingWithFreshnessTagKeyThrows() {
        ChipFactory factory = new BaseFactory() {
            @Override
            public Map<String, String> freshnessTags(String table) {
                return Map.of("schema_version", "v2");
            }

            @Override
            public Map<String, String> additionalTags(String table, String partitionValue) {
                return Map.of("schema_version", "v3");
            }
        };

        assertThrows(IllegalArgumentException.class, () ->
                resolver.resolveForTables(Set.of("users"), List.of(), "tenant", "42", factory));
        assertEquals(0, engine.searches.size());
    }
}
