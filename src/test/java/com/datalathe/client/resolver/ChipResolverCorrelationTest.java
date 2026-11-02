package com.datalathe.client.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChipResolverCorrelationTest {

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

    private MockWebServer server;
    private DatalatheClient client;
    private ExecutorService executor;
    private ChipResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
        executor = Executors.newFixedThreadPool(4);
        resolver = new ChipResolver(client, executor, 1, 30);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.shutdown();
    }

    private void enqueueEmptySearch() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chips\":[],\"metadata\":[]}"));
    }

    private MockResponse created(String chipId) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chip_id\":\"" + chipId + "\"}");
    }

    private ResolvedChips resolve(ChipResolver r, String tagValue) throws Exception {
        return r.resolveForTables(Set.of("users"), List.of(), "tenant", tagValue, FACTORY);
    }

    private RecordedRequest take() throws InterruptedException {
        return server.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    void scopedResolverSendsHeaderOnSearchAndCreate() throws Exception {
        enqueueEmptySearch();
        server.enqueue(created("c1"));

        ResolvedChips chips = resolve(resolver.withCorrelationId("run-1"), "42");

        assertEquals(List.of("c1"), chips.allChipIds());
        assertEquals("run-1", take().getHeader("X-Correlation-ID"));
        assertEquals("run-1", take().getHeader("X-Correlation-ID"));
    }

    @Test
    void baseResolverStaysUntagged() throws Exception {
        resolver.withCorrelationId("run-1");
        enqueueEmptySearch();
        server.enqueue(created("c1"));

        resolve(resolver, "42");

        assertNull(take().getHeader("X-Correlation-ID"));
        assertNull(take().getHeader("X-Correlation-ID"));
    }

    @Test
    void blankIdReturnsSameResolver() {
        assertSame(resolver, resolver.withCorrelationId(null));
        assertSame(resolver, resolver.withCorrelationId(" "));
        assertSame(resolver, resolver.withCorrelationId("bad id\n"));
        assertNotSame(resolver, resolver.withCorrelationId("ok"));
    }

    @Test
    void scopedResolversShareInFlightCreates() throws Exception {
        ChipResolver first = resolver.withCorrelationId("run-1");
        ChipResolver second = resolver.withCorrelationId("run-2");

        enqueueEmptySearch();
        server.enqueue(created("c1").setBodyDelay(1500, TimeUnit.MILLISECONDS));
        enqueueEmptySearch();

        Future<ResolvedChips> firstResult = executor.submit(() -> resolve(first, "42"));
        RecordedRequest firstSearch = take();
        RecordedRequest create = take();
        assertEquals("run-1", firstSearch.getHeader("X-Correlation-ID"));
        assertEquals("run-1", create.getHeader("X-Correlation-ID"));
        assertEquals(1, second.inflightCount());

        ResolvedChips secondResult = resolve(second, "42");

        assertEquals(List.of("c1"), secondResult.allChipIds());
        assertEquals(List.of("c1"), firstResult.get(5, TimeUnit.SECONDS).allChipIds());
        assertEquals("run-2", take().getHeader("X-Correlation-ID"));
        assertEquals(3, server.getRequestCount());
    }
}
