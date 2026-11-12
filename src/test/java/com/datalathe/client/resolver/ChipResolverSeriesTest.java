package com.datalathe.client.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChipResolverSeriesTest {

    private static final ChipFactory FACTORY = new ChipFactory() {
        @Override
        public boolean isPartitioned(String table) {
            return false;
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
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private ExecutorService executor;
    private ChipResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
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

    private JsonNode createBody(ChipResolver r, String tagKey, String tagValue) throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chips\":[],\"metadata\":[]}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chip_id\":\"c1\"}"));
        ResolvedChips chips = r.resolveForTables(Set.of("users"), List.of(), tagKey, tagValue, FACTORY);
        assertEquals(List.of("c1"), chips.allChipIds());
        server.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest create = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/lathe/stage/data", create.getPath());
        return mapper.readTree(create.getBody().readUtf8());
    }

    @Test
    void aResolverWithoutASeriesSendsNoSeriesField() throws Exception {
        assertFalse(createBody(resolver, "tenant", "1234").has("series"));
    }

    @Test
    void aSeriesResolverPublishesIntoTheSeriesNamedByTheTag() throws Exception {
        JsonNode body = createBody(resolver.withSeries(SeriesNamer.tagValue("tenant")), "tenant", "1234");

        assertEquals("1234", body.get("series").get("id").asText());
        assertFalse(body.get("series").has("scope"));
        assertFalse(body.get("series").has("contract_major"));
        assertEquals("1234", body.get("tags").get("tenant").asText());
    }

    @Test
    void aTagThatTheNamerDoesNotCoverStaysOutOfAnySeries() throws Exception {
        assertFalse(createBody(resolver.withSeries(SeriesNamer.tagValue("tenant")), "pool", "77").has("series"));
    }

    @Test
    void anInvalidSeriesIdDoesNotFailTheCreate() throws Exception {
        ChipResolver series = resolver.withSeries((key, value) -> "tenant/" + value);

        assertFalse(createBody(series, "tenant", "1234").has("series"));
    }

    @Test
    void theSeriesSurvivesACorrelationScope() throws Exception {
        ChipResolver scoped = resolver.withSeries((key, value) -> key + ":" + value).withCorrelationId("run-1");

        assertTrue(createBody(scoped, "tenant", "1234").get("series").get("id").asText().equals("tenant:1234"));
    }

    @Test
    void theBaseResolverIsUnchangedByWithSeries() throws Exception {
        resolver.withSeries(SeriesNamer.tagValue("tenant"));

        assertFalse(createBody(resolver, "tenant", "1234").has("series"));
    }
}
