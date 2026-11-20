package com.datalathe.client.resolver;

import static com.datalathe.client.resolver.ScriptedEngine.chip;
import static com.datalathe.client.resolver.ScriptedEngine.found;
import static com.datalathe.client.resolver.ScriptedEngine.tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChipResolverStatsTest {

    private MockWebServer server;
    private ScriptedEngine engine;
    private ExecutorService executor;
    private ExecutorService callers;
    private ChipResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        engine = new ScriptedEngine();
        server.setDispatcher(engine);
        server.start();
        DatalatheClient client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
        executor = Executors.newFixedThreadPool(4);
        callers = Executors.newFixedThreadPool(2);
        resolver = new ChipResolver(client, executor, 1, 30);
    }

    @AfterEach
    void tearDown() throws Exception {
        callers.shutdownNow();
        executor.shutdownNow();
        server.shutdown();
    }

    private static ChipFactory factory(Map<String, String> freshness) {
        return new ChipFactory() {
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

            @Override
            public Map<String, String> freshnessTags(String table) {
                return freshness;
            }
        };
    }

    @Test
    void foundChipsAreCountedWithoutCreates() throws Exception {
        engine.search(found(chip("c1", "users", null), tag("c1", "tenant", "42")));

        ResolveStats stats = resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42", factory(null)).stats();

        assertEquals(1, stats.chipsFound());
        assertEquals(0, stats.chipsCreated());
        assertEquals(0, stats.chipsAwaited());
        assertEquals(0, stats.chipsEvicted());
        assertFalse(stats.searchShared());
        assertEquals(0, stats.createMillis());
        assertEquals(0, stats.waitMillis());
        assertTrue(stats.totalMillis() >= stats.searchMillis());
    }

    @Test
    void createsAreCountedAndTimedThroughACorrelationView() throws Exception {
        engine.search(found(chip("c1", "users", null), tag("c1", "tenant", "42")));
        ScriptedEngine.Reply orders = engine.heldCreate("orders", "c2");
        ScriptedEngine.Reply items = engine.heldCreate("items", "c3");

        Future<ResolvedChips> pending = callers.submit(() -> resolver.withCorrelationId("run-1")
                .resolveForTables(Set.of("users", "orders", "items"), List.of(),
                        "tenant", "42", factory(null)));
        orders.awaitArrived();
        items.awaitArrived();
        Thread.sleep(300);
        orders.release();
        items.release();

        ResolveStats stats = pending.get(10, TimeUnit.SECONDS).stats();
        assertEquals(1, stats.chipsFound());
        assertEquals(2, stats.chipsCreated());
        assertEquals(0, stats.chipsAwaited());
        assertTrue(stats.createMillis() >= 400, "createMillis sums both creates: " + stats);
        assertTrue(stats.totalMillis() >= 200, stats.toString());
        assertEquals(0, stats.waitMillis());
    }

    @Test
    void chipsBuiltByAnotherCallAreCountedAsAwaited() throws Exception {
        ScriptedEngine.Reply create = engine.heldCreate("users", "c1");

        Future<ResolvedChips> builder = callers.submit(() -> resolver.withCorrelationId("run-1")
                .resolveForTables(Set.of("users"), List.of(), "tenant", "42", factory(null)));
        create.awaitArrived();
        Future<ResolvedChips> waiter = callers.submit(() -> resolver.withCorrelationId("run-2")
                .resolveForTables(Set.of("users"), List.of(), "tenant", "42", factory(null)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (engine.searches.size() < 2) {
            assertTrue(System.nanoTime() < deadline, "second search never arrived");
            Thread.sleep(5);
        }
        Thread.sleep(400);
        create.release();

        ResolveStats built = builder.get(10, TimeUnit.SECONDS).stats();
        ResolveStats waited = waiter.get(10, TimeUnit.SECONDS).stats();
        assertEquals(1, built.chipsCreated());
        assertEquals(0, built.chipsAwaited());
        assertEquals(0, waited.chipsCreated());
        assertEquals(1, waited.chipsAwaited());
        assertEquals(0, waited.createMillis());
        assertTrue(waited.waitMillis() >= 200, waited.toString());
        assertEquals(1, engine.creates.size());
    }

    @Test
    void evictionsAreCounted() throws Exception {
        engine.search(found(chip("c1", "users", null),
                tag("c1", "tenant", "42") + "," + tag("c1", "schema_version", "v1")));
        engine.create("users", "c2");

        ResolveStats stats = resolver.resolveForTables(Set.of("users"), List.of(),
                "tenant", "42", factory(Map.of("schema_version", "v2"))).stats();

        assertEquals(1, stats.chipsEvicted());
        assertEquals(0, stats.chipsFound());
        assertEquals(1, stats.chipsCreated());
    }

    @Test
    void resolvedChipsBuiltWithoutStatsReportZeros() {
        ResolvedChips chips = new ResolvedChips(List.of("a"), List.of("b"));

        assertSame(ResolveStats.NONE, chips.stats());
        assertEquals(0, chips.stats().chipsFound());
        assertEquals(0, chips.stats().totalMillis());
    }
}
