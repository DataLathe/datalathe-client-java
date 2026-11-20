package com.datalathe.client.resolver;

import static com.datalathe.client.resolver.ScriptedEngine.chip;
import static com.datalathe.client.resolver.ScriptedEngine.found;
import static com.datalathe.client.resolver.ScriptedEngine.tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChipResolverSharedSearchTest {

    private static final String USERS_C1 = found(chip("c1", "users", null), tag("c1", "tenant", "42"));

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
    }

    @AfterEach
    void tearDown() throws Exception {
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

    private static ResolvedChips resolve(ChipResolver r, String table, String tagValue,
                                         Map<String, String> freshness) throws IOException {
        return r.resolveForTables(Set.of(table), List.of(), "tenant", tagValue, factory(freshness));
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
                throw new AssertionError(thread.getName() + " never waited on the shared search");
            }
            Thread.sleep(5);
        }
    }

    private static ResolvedChips get(FutureTask<ResolvedChips> task) throws Exception {
        return task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void concurrentResolvesShareOneSearch() throws Exception {
        ScriptedEngine.Reply search = engine.heldSearch(USERS_C1);

        List<FutureTask<ResolvedChips>> tasks = new ArrayList<>();
        tasks.add(start(() -> resolve(resolver, "users", "42", null)));
        search.awaitArrived();
        for (int i = 1; i < 8; i++) {
            tasks.add(start(() -> resolve(resolver, "users", "42", null)));
        }
        for (Thread joiner : threads.subList(1, threads.size())) {
            awaitParked(joiner);
        }
        search.release();

        int shared = 0;
        for (FutureTask<ResolvedChips> task : tasks) {
            ResolvedChips chips = get(task);
            assertEquals(List.of("c1"), chips.allChipIds());
            if (chips.stats().searchShared()) {
                shared++;
            }
        }
        assertEquals(1, engine.searches.size());
        assertEquals(7, shared);
        assertEquals(0, engine.creates.size());
    }

    @Test
    void correlationViewsShareOneSearchWithTheStartersId() throws Exception {
        ScriptedEngine.Reply search = engine.heldSearch(USERS_C1);

        FutureTask<ResolvedChips> first =
                start(() -> resolve(resolver.withCorrelationId("run-1"), "users", "42", null));
        search.awaitArrived();
        FutureTask<ResolvedChips> second =
                start(() -> resolve(resolver.withCorrelationId("run-2"), "users", "42", null));
        awaitParked(threads.get(1));
        search.release();

        assertEquals(List.of("c1"), get(first).allChipIds());
        assertEquals(List.of("c1"), get(second).allChipIds());
        assertEquals(1, engine.searches.size());
        assertEquals("run-1", engine.searches.get(0).getHeader("X-Correlation-ID"));
    }

    @Test
    void differentTagValuesSearchSeparately() throws Exception {
        ScriptedEngine.Reply firstSearch = engine.heldSearch(USERS_C1);
        ScriptedEngine.Reply secondSearch = engine.heldSearch(
                found(chip("c9", "users", null), tag("c9", "tenant", "43")));

        FutureTask<ResolvedChips> first = start(() -> resolve(resolver, "users", "42", null));
        firstSearch.awaitArrived();
        FutureTask<ResolvedChips> second = start(() -> resolve(resolver, "users", "43", null));
        secondSearch.awaitArrived();
        firstSearch.release();
        secondSearch.release();

        assertEquals(List.of("c1"), get(first).allChipIds());
        assertEquals(List.of("c9"), get(second).allChipIds());
        assertEquals(2, engine.searches.size());
    }

    @Test
    void resolvesThatDoNotOverlapEachSearch() throws Exception {
        engine.search(USERS_C1);
        engine.search(USERS_C1);

        assertFalse(resolve(resolver, "users", "42", null).stats().searchShared());
        assertFalse(resolve(resolver, "users", "42", null).stats().searchShared());

        assertEquals(2, engine.searches.size());
    }

    @Test
    void resolveAfterACreateDoesNotJoinAnOlderSearch() throws Exception {
        engine.search(ScriptedEngine.NO_CHIPS);
        ScriptedEngine.Reply create = engine.heldCreate("users", "c1");
        FutureTask<ResolvedChips> creator = start(() -> resolve(resolver, "users", "42", null));
        create.awaitArrived();

        ScriptedEngine.Reply olderSearch = engine.heldSearch(ScriptedEngine.NO_CHIPS);
        FutureTask<ResolvedChips> older = start(() -> resolve(resolver, "users", "42", null));
        olderSearch.awaitArrived();

        create.release();
        assertEquals(List.of("c1"), get(creator).allChipIds());

        engine.search(USERS_C1);
        ResolvedChips after = resolve(resolver, "users", "42", null);
        assertEquals(List.of("c1"), after.allChipIds());
        assertFalse(after.stats().searchShared());
        assertEquals(1, after.stats().chipsFound());
        assertEquals(3, engine.searches.size());

        olderSearch.release();
        ResolvedChips fromOlder = get(older);
        assertEquals(List.of("c1"), fromOlder.allChipIds());
        assertEquals(1, fromOlder.stats().chipsAwaited());
        assertEquals(1, engine.creates.size());

        engine.search(USERS_C1);
        resolve(resolver, "users", "42", null);
        assertEquals(0, resolver.inflightCount());
    }

    @Test
    void olderSearchDoesNotReturnAChipEvictedSinceItStarted() throws Exception {
        String staleListing = found(chip("old", "users", null),
                tag("old", "tenant", "42") + "," + tag("old", "schema_version", "v1"));

        engine.search(ScriptedEngine.NO_CHIPS);
        ScriptedEngine.Reply otherCreate = engine.heldCreate("other", "c-other");
        FutureTask<ResolvedChips> creator = start(() -> resolve(resolver, "other", "42", null));
        otherCreate.awaitArrived();

        ScriptedEngine.Reply olderSearch = engine.heldSearch(staleListing);
        FutureTask<ResolvedChips> older = start(() -> resolve(resolver, "users", "42", null));
        olderSearch.awaitArrived();

        otherCreate.release();
        get(creator);

        engine.search(staleListing);
        engine.create("users", "new");
        ResolvedChips evicting = resolve(resolver, "users", "42", Map.of("schema_version", "v2"));
        assertEquals(List.of("new"), evicting.allChipIds());
        assertEquals(List.of("old"), engine.deletes);

        olderSearch.release();
        assertEquals(List.of("new"), get(older).allChipIds());
        assertEquals(2, engine.creates.size());
    }

    @Test
    void chipCreatedHereThenEvictedIsNotReturnedAgain() throws Exception {
        engine.search(ScriptedEngine.NO_CHIPS);
        engine.create("users", "c1");
        assertEquals(List.of("c1"),
                resolve(resolver, "users", "42", Map.of("schema_version", "v1")).allChipIds());

        engine.search(found(chip("c1", "users", null),
                tag("c1", "tenant", "42") + "," + tag("c1", "schema_version", "v1")));
        engine.create("users", "c2");
        ResolvedChips resolved = resolve(resolver, "users", "42", Map.of("schema_version", "v2"));

        assertEquals(List.of("c2"), resolved.allChipIds());
        assertEquals(List.of("c1"), engine.deletes);
        assertEquals(2, engine.creates.size());
    }

    @Test
    void resolveSharingAFailedSearchSendsItsOwn() throws Exception {
        ScriptedEngine.Reply failing = engine.heldFailingSearch();
        engine.search(USERS_C1);

        FutureTask<ResolvedChips> owner = start(() -> resolve(resolver, "users", "42", null));
        failing.awaitArrived();
        FutureTask<ResolvedChips> joiner = start(() -> resolve(resolver, "users", "42", null));
        awaitParked(threads.get(1));
        failing.release();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> get(owner));
        assertTrue(failure.getCause() instanceof IOException);
        assertEquals(List.of("c1"), get(joiner).allChipIds());
        assertEquals(2, engine.searches.size());
    }
}
