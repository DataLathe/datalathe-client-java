package com.datalathe.client.resolver;

import static com.datalathe.client.resolver.ScriptedEngine.chip;
import static com.datalathe.client.resolver.ScriptedEngine.found;
import static com.datalathe.client.resolver.ScriptedEngine.tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChipResolverEvictionTest {

    private static final int RESOLVES = 12;
    private static final List<String> TABLES = List.of("t1", "t2", "t3", "t4", "t5");
    private static final Map<String, String> V2 = Map.of("schema_version", "v2");

    private MockWebServer server;
    private ScriptedEngine engine;
    private ExecutorService executor;
    private ChipResolver resolver;
    private final List<Thread> threads = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        engine = new ScriptedEngine();
        server.setDispatcher(engine);
        server.start();
        DatalatheClient client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
        executor = Executors.newFixedThreadPool(4);
        resolver = new ChipResolver(client, executor, 1, 30);
        resolver.evictRecheckDelaysMillis = new long[] {5, 5, 5};
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
                ChipSource.ChipSourceBuilder source = ChipSource.builder()
                        .sourceType(SourceType.MYSQL)
                        .databaseName("db")
                        .tableName(table)
                        .query("SELECT * FROM " + table);
                if (partitionValue != null) {
                    source.partition(ChipSource.Partition.builder()
                            .partitionBy("month")
                            .partitionValues(List.of(partitionValue))
                            .build());
                }
                return source.build();
            }

            @Override
            public Map<String, String> freshnessTags(String table) {
                return freshness;
            }
        };
    }

    private static ResolvedChips resolve(ChipResolver r, Set<String> tables,
                                         Map<String, String> freshness) throws Exception {
        return r.resolveForTables(tables, List.of(), "tenant", "42", factory(false, freshness));
    }

    private static String staleListing(List<String> tables) {
        List<String> chips = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        for (String table : tables) {
            String id = "old-" + table;
            chips.add(chip(id, table, null));
            tags.add(tag(id, "tenant", "42"));
            tags.add(tag(id, "schema_version", "v1"));
        }
        return found(String.join(",", chips), String.join(",", tags));
    }

    private FutureTask<ResolvedChips> start(Callable<ResolvedChips> call) {
        FutureTask<ResolvedChips> task = new FutureTask<>(call);
        Thread thread = new Thread(task, "resolve-" + threads.size());
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
        return task;
    }

    private void awaitParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(thread.getName() + " never waited");
            }
            Thread.sleep(5);
        }
    }

    private static ResolvedChips get(FutureTask<ResolvedChips> task) throws Exception {
        return task.get(10, TimeUnit.SECONDS);
    }

    private List<FutureTask<ResolvedChips>> startSharedBurst() throws Exception {
        ScriptedEngine.Reply search = engine.heldSearch(staleListing(TABLES));
        List<ChipResolver> views = List.of(
                resolver,
                resolver.withCorrelationId("run-a"),
                resolver.withCorrelationId("run-b"),
                resolver.withSeries((tagKey, tagValue) -> "series-" + tagValue));
        List<FutureTask<ResolvedChips>> tasks = new ArrayList<>();
        for (int i = 0; i < RESOLVES; i++) {
            ChipResolver view = views.get(i % views.size());
            tasks.add(start(() -> resolve(view, new HashSet<>(TABLES), V2)));
            if (i == 0) {
                search.awaitArrived();
            }
        }
        for (Thread joiner : threads.subList(1, threads.size())) {
            awaitParked(joiner);
        }
        search.release();
        return tasks;
    }

    private static Set<String> replacements() {
        Set<String> ids = new TreeSet<>();
        for (String table : TABLES) {
            ids.add("new-" + table);
        }
        return ids;
    }

    @Test
    void concurrentResolvesSendOneDeletePerStaleChip() throws Exception {
        ScriptedEngine.Reply firstDelete = engine.heldDelete("old-t1");
        for (String table : TABLES) {
            engine.create(table, "new-" + table);
        }

        List<FutureTask<ResolvedChips>> tasks = startSharedBurst();
        firstDelete.awaitArrived();
        firstDelete.release();

        int evicted = 0;
        int created = 0;
        int awaited = 0;
        for (FutureTask<ResolvedChips> task : tasks) {
            ResolvedChips chips = get(task);
            assertEquals(replacements(), new TreeSet<>(chips.allChipIds()));
            evicted += chips.stats().chipsEvicted();
            created += chips.stats().chipsCreated();
            awaited += chips.stats().chipsAwaited();
        }
        assertEquals(TABLES.size(), engine.deletes.size(), engine.deletes.toString());
        assertEquals(TABLES.size(), new HashSet<>(engine.deletes).size());
        assertEquals(TABLES.size(), engine.creates.size());
        assertEquals(TABLES.size(), evicted);
        assertEquals(TABLES.size(), created);
        assertEquals(TABLES.size() * (RESOLVES - 1), awaited);
        assertEquals(1, engine.searches.size());
    }

    @Test
    void racingDeleteThatFailsBeforeTheChipDisappearsStillRebuilds() throws Exception {
        for (String table : TABLES) {
            engine.failingDelete("old-" + table);
            engine.lookup("old-" + table, 200, 500, 404);
            engine.create(table, "new-" + table);
        }

        List<FutureTask<ResolvedChips>> tasks = startSharedBurst();

        int evicted = 0;
        for (FutureTask<ResolvedChips> task : tasks) {
            ResolvedChips chips = get(task);
            assertEquals(replacements(), new TreeSet<>(chips.allChipIds()));
            evicted += chips.stats().chipsEvicted();
        }
        assertEquals(TABLES.size(), engine.deletes.size(), engine.deletes.toString());
        assertEquals(TABLES.size() * 3, engine.lookups.size());
        assertEquals(TABLES.size(), engine.creates.size());
        assertEquals(TABLES.size(), evicted);
    }

    @Test
    void resolveWithItsOwnListingWaitsForTheEvictionInFlight() throws Exception {
        String listing = staleListing(List.of("t1"));
        ScriptedEngine.Reply delete = engine.heldDelete("old-t1");
        engine.create("t1", "new-t1");

        engine.search(listing);
        FutureTask<ResolvedChips> evictor = start(() -> resolve(resolver, Set.of("t1"), V2));
        delete.awaitArrived();

        engine.search(listing);
        FutureTask<ResolvedChips> waiter = start(() -> resolve(resolver, Set.of("t1"), V2));
        awaitParked(threads.get(1));
        Thread.sleep(100);
        delete.release();

        ResolveStats evictorStats = get(evictor).stats();
        ResolveStats waiterStats = get(waiter).stats();
        assertEquals(List.of("new-t1"), get(evictor).allChipIds());
        assertEquals(List.of("new-t1"), get(waiter).allChipIds());
        assertEquals(2, engine.searches.size());
        assertEquals(List.of("old-t1"), engine.deletes);
        assertEquals(1, engine.creates.size());
        assertEquals(1, evictorStats.chipsEvicted());
        assertEquals(0, waiterStats.chipsEvicted());
        assertEquals(0, waiterStats.chipsFound());
        assertEquals(1, waiterStats.chipsCreated() + waiterStats.chipsAwaited());
        assertTrue(waiterStats.waitMillis() >= 90, waiterStats.toString());
    }

    @Test
    void chipEvictedWhileAnOlderListingIsWalkedIsNotDeletedAgain() throws Exception {
        String listing = staleListing(List.of("t1", "t2"));
        ScriptedEngine.Reply firstDelete = engine.heldDelete("old-t1");
        engine.create("t1", "new-t1");
        engine.create("t2", "new-t2");

        engine.search(listing);
        FutureTask<ResolvedChips> walking = start(() -> resolve(resolver, Set.of("t1", "t2"), V2));
        firstDelete.awaitArrived();

        engine.search(listing);
        assertEquals(List.of("new-t2"), resolve(resolver, Set.of("t2"), V2).allChipIds());

        firstDelete.release();
        ResolvedChips chips = get(walking);

        assertEquals(Set.of("new-t1", "new-t2"), new HashSet<>(chips.allChipIds()));
        assertEquals(List.of("old-t1", "old-t2"), engine.deletes);
        assertEquals(2, engine.creates.size());
        assertEquals(1, chips.stats().chipsEvicted());
        assertEquals(1, chips.stats().chipsCreated());
        assertEquals(1, chips.stats().chipsAwaited());
    }

    @Test
    void finishedCreateOfAChipBeingEvictedIsNotReturned() throws Exception {
        engine.search(ScriptedEngine.NO_CHIPS);
        engine.create("t1", "old-t1");
        assertEquals(List.of("old-t1"),
                resolve(resolver, Set.of("t1"), Map.of("schema_version", "v1")).allChipIds());

        ScriptedEngine.Reply delete = engine.heldDelete("old-t1");
        engine.create("t1", "new-t1");
        engine.search(staleListing(List.of("t1")));
        FutureTask<ResolvedChips> evictor = start(() -> resolve(resolver, Set.of("t1"), V2));
        delete.awaitArrived();

        engine.search(ScriptedEngine.NO_CHIPS);
        FutureTask<ResolvedChips> other = start(() -> resolve(resolver, Set.of("t1"), V2));
        awaitParked(threads.get(1));
        delete.release();

        assertEquals(List.of("new-t1"), get(evictor).allChipIds());
        assertEquals(List.of("new-t1"), get(other).allChipIds());
        assertEquals(List.of("old-t1"), engine.deletes);
        assertEquals(2, engine.creates.size());
        assertEquals(0, get(other).stats().chipsEvicted());
    }

    @Test
    void failedDeleteOfAChipThatStaysKeepsItForEveryWaitingResolve() throws Exception {
        String listing = staleListing(List.of("t1"));
        ScriptedEngine.Reply delete = engine.heldFailingDelete("old-t1");

        engine.search(listing);
        FutureTask<ResolvedChips> evictor = start(() -> resolve(resolver, Set.of("t1"), V2));
        delete.awaitArrived();

        engine.search(listing);
        FutureTask<ResolvedChips> waiter = start(() -> resolve(resolver, Set.of("t1"), V2));
        awaitParked(threads.get(1));
        delete.release();

        for (FutureTask<ResolvedChips> task : List.of(evictor, waiter)) {
            ResolvedChips chips = get(task);
            assertEquals(List.of("old-t1"), chips.allChipIds());
            assertEquals(1, chips.stats().chipsFound());
            assertEquals(0, chips.stats().chipsEvicted());
            assertEquals(0, chips.stats().chipsCreated());
        }
        assertEquals(List.of("old-t1"), engine.deletes);
        assertEquals(1 + resolver.evictRecheckDelaysMillis.length, engine.lookups.size());
        assertEquals(0, engine.creates.size());
    }

    @Test
    void chipDeletedByAnotherServerDuringTheRecheckIsReplaced() throws Exception {
        ScriptedEngine.Reply delete = engine.heldFailingDelete("old-t1");
        engine.create("t1", "new-t1");
        engine.search(staleListing(List.of("t1")));
        resolver.evictRecheckDelaysMillis = new long[] {20, 20, 20, 20, 5000};

        FutureTask<ResolvedChips> task = start(() -> resolve(resolver, Set.of("t1"), V2));
        delete.awaitArrived();
        delete.release();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (engine.lookups.isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "no lookup after the failed delete");
            Thread.sleep(2);
        }
        engine.vanish("old-t1");

        ResolvedChips chips = get(task);
        assertEquals(List.of("new-t1"), chips.allChipIds());
        assertEquals(List.of("old-t1"), engine.deletes);
        assertEquals(1, chips.stats().chipsEvicted());
        assertEquals(1, chips.stats().chipsCreated());
    }

    @Test
    void keptChipListedForSeveralPartitionsGetsOneDeleteAndOneRecheck() throws Exception {
        engine.search(found(
                chip("old", "loans", "2026-01") + "," + chip("old", "loans", "2026-02"),
                tag("old", "tenant", "42") + "," + tag("old", "schema_version", "v1")));
        engine.failingDelete("old");

        ResolvedChips chips = resolver.resolveForTables(Set.of("loans"), List.of("2026-01", "2026-02"),
                "tenant", "42", factory(true, V2));

        assertEquals(Set.of("old"), new HashSet<>(chips.allChipIds()));
        assertEquals(List.of("old"), engine.deletes);
        assertEquals(1 + resolver.evictRecheckDelaysMillis.length, engine.lookups.size());
        assertEquals(0, engine.creates.size());
        assertEquals(0, chips.stats().chipsEvicted());
    }
}
