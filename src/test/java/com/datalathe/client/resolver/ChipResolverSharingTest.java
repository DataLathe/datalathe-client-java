package com.datalathe.client.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The in-flight map is per instance, so dedup only holds when every caller
 * shares one resolver. A lazily initialized singleton without synchronization
 * hands concurrent callers their own instances and quietly loses it.
 */
class ChipResolverSharingTest {

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
    private final AtomicInteger creates = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        creates.set(0);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.contains("/stage/data")) {
                    creates.incrementAndGet();
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"chip_ids\":[\"chip-1\"]}");
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"chips\":[],\"metadata\":[]}");
            }
        });
        server.start();
        client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.shutdown();
    }

    private void resolveConcurrently(java.util.function.IntFunction<ChipResolver> resolverFor)
            throws Exception {
        int callers = 4;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        for (int i = 0; i < callers; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    ChipResolver r = resolverFor.apply(idx);
                    ready.countDown();
                    go.await();
                    r.resolveForTables(Set.of("loan04"), List.of(), "tenant", "555", FACTORY);
                } catch (Exception ignored) {
                    // failures surface as a create count that misses the assertion
                } finally {
                    done.countDown();
                }
            }).start();
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        done.await(30, TimeUnit.SECONDS);
    }

    @Test
    void oneSharedResolverCreatesTheChipOnce() throws Exception {
        ChipResolver shared = new ChipResolver(client, executor, 1);
        resolveConcurrently(i -> shared);
        assertEquals(1, creates.get());
    }

    @Test
    void aResolverPerCallerCreatesTheChipPerCaller() throws Exception {
        resolveConcurrently(i -> new ChipResolver(client, executor, 1));
        assertEquals(4, creates.get());
    }
}
