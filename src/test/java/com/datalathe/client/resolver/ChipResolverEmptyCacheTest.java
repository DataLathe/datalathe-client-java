package com.datalathe.client.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChipResolverEmptyCacheTest {

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

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.shutdown();
    }

    private ChipResolver resolver(long emptyRecheckMinutes) {
        return new ChipResolver(client, executor, 1, emptyRecheckMinutes);
    }

    private void enqueueEmptySearch() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chips\":[],\"metadata\":[]}"));
    }

    private void enqueueCreateFailure(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private ResolvedChips resolve(ChipResolver resolver, String tagValue) throws Exception {
        return resolver.resolveForTables(Set.of("users"), List.of(), "tenant", tagValue, FACTORY);
    }

    @Test
    void emptySourceErrorCodeSuppressesRetry() throws Exception {
        ChipResolver resolver = resolver(30);
        enqueueEmptySearch();
        enqueueCreateFailure("{\"error_code\":\"EMPTY_SOURCE\",\"message\":\"no rows\"}");

        ResolvedChips first = resolve(resolver, "42");
        assertTrue(first.allChipIds().isEmpty());
        assertEquals(2, server.getRequestCount());

        enqueueEmptySearch();
        ResolvedChips second = resolve(resolver, "42");
        assertTrue(second.allChipIds().isEmpty());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void legacyEmptyMessageSuppressesRetry() throws Exception {
        ChipResolver resolver = resolver(30);
        enqueueEmptySearch();
        enqueueCreateFailure("{\"error_code\":\"PROCESSING_ERROR\","
                + "\"message\":\"No partitions to register - all partition values returned empty data\"}");

        resolve(resolver, "42");
        assertEquals(2, server.getRequestCount());

        enqueueEmptySearch();
        resolve(resolver, "42");
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void windowExpiryAllowsFreshAttempt() throws Exception {
        ChipResolver resolver = resolver(30);
        long[] now = {0L};
        resolver.clock = () -> now[0];

        enqueueEmptySearch();
        enqueueCreateFailure("{\"error_code\":\"EMPTY_SOURCE\",\"message\":\"no rows\"}");
        resolve(resolver, "42");
        assertEquals(2, server.getRequestCount());

        now[0] += TimeUnit.MINUTES.toMillis(31);
        enqueueEmptySearch();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chip_id\":\"c1\"}"));

        ResolvedChips resolved = resolve(resolver, "42");
        assertEquals(4, server.getRequestCount());
        assertEquals(List.of("c1"), resolved.allChipIds());
    }

    @Test
    void differentTagValueIsNotSuppressed() throws Exception {
        ChipResolver resolver = resolver(30);
        enqueueEmptySearch();
        enqueueCreateFailure("{\"error_code\":\"EMPTY_SOURCE\",\"message\":\"no rows\"}");
        resolve(resolver, "42");
        assertEquals(2, server.getRequestCount());

        enqueueEmptySearch();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chip_id\":\"c2\"}"));

        ResolvedChips resolved = resolve(resolver, "43");
        assertEquals(4, server.getRequestCount());
        assertEquals(List.of("c2"), resolved.allChipIds());
    }

    @Test
    void zeroWindowDisablesSuppression() throws Exception {
        ChipResolver resolver = resolver(0);
        enqueueEmptySearch();
        enqueueCreateFailure("{\"error_code\":\"EMPTY_SOURCE\",\"message\":\"no rows\"}");
        resolve(resolver, "42");
        assertEquals(2, server.getRequestCount());

        enqueueEmptySearch();
        enqueueCreateFailure("{\"error_code\":\"EMPTY_SOURCE\",\"message\":\"no rows\"}");
        resolve(resolver, "42");
        assertEquals(4, server.getRequestCount());
    }
}
