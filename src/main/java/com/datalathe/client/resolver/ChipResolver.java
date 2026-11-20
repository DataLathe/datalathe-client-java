package com.datalathe.client.resolver;

import com.datalathe.client.ChipNotFoundException;
import com.datalathe.client.DatalatheApiException;
import com.datalathe.client.DatalatheClient;
import com.datalathe.client.SearchChipsResponse;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SeriesRef;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Resolves the set of chips needed for a report, creating any that are missing.
 *
 * <p>Given a set of table names (or SQL queries to parse), partition values, and
 * a tag for tenant isolation, the resolver:</p>
 * <ol>
 *   <li>Searches the engine for existing chips matching the tag, sharing one
 *       search among resolves for the same tag that overlap in time</li>
 *   <li>Diffs against what's needed, splitting tables into partitioned (one chip
 *       per partition value) and unpartitioned (one chip total) via the
 *       {@link ChipFactory}</li>
 *   <li>Creates missing chips in parallel, deduplicating concurrent requests for
 *       the same chip</li>
 *   <li>Tags new chips for future lookups</li>
 * </ol>
 *
 * <p>Create one resolver per application and share it across threads. The
 * built-in gate ensures that if two threads both need the same chip, only one
 * API call is made — the other joins the same future.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ChipResolver resolver = new ChipResolver(client);
 *
 * // From SQL — extracts table names automatically
 * ResolvedChips chips = resolver.resolve(
 *     List.of("SELECT ls.*, ld.status FROM loan_summary ls JOIN loan_delinquency ld ..."),
 *     List.of("2025-03", "2025-04", ..., "2026-03"),
 *     "tenant", "42",
 *     myFactory);
 *
 * // Or from known table names
 * ResolvedChips chips = resolver.resolveForTables(
 *     Set.of("loan_summary", "loan_delinquency"),
 *     List.of("2025-03", ..., "2026-03"),
 *     "tenant", "42",
 *     myFactory);
 *
 * client.generateReport(chips.allChipIds(), queries);
 * }</pre>
 *
 * <h3>Incremental creation</h3>
 * <p>On the first run for a 13-month trend report, the resolver creates all chips.
 * On subsequent runs it finds them via search and creates nothing. When the window
 * slides forward one month, only the single new partitioned chip per partitioned
 * table is created — the other 12 months already exist.</p>
 *
 * <h3>Shared search</h3>
 * <p>Resolves for the same tag key and value that overlap in time share one
 * chip search: a resolve that starts while a search for its tag is in flight
 * waits for that search instead of sending another, unless this resolver has
 * created or evicted a chip for the tag since the search started. Nothing is
 * cached once the search returns, so a resolve that starts later sends its
 * own. Sharing spans {@link #withCorrelationId} and {@link #withSeries} views;
 * a shared search carries the correlation ID of the resolve that sent it.</p>
 *
 * <h3>Statistics</h3>
 * <p>{@link ResolvedChips#stats()} reports what each resolve call did: chips
 * found, created, awaited and evicted, and the time spent on the search, on
 * creates and on waiting for creates that other calls started.</p>
 *
 * <h3>Freshness</h3>
 * <p>Chips are snapshots of their source; by default the resolver serves a found
 * chip forever. A factory can opt a table into staleness tracking by returning
 * expected tag entries from {@link ChipFactory#freshnessTags}. The resolver stamps
 * those tags atomically when it creates the table's chips and, on every resolve,
 * deletes any matched chip whose tags are missing an entry or hold a different
 * value, then creates the replacement in the same pass — callers never see the
 * eviction. Semantics, convergence guarantee, and caveats (other writers'
 * untagged chips, partitioned-table mass re-stage, at-least-once eviction under
 * concurrent resolvers) are documented on {@link ChipFactory#freshnessTags}.</p>
 */
public class ChipResolver {

    private static final Logger log = LogManager.getLogger(ChipResolver.class);
    private static final long DEFAULT_TIMEOUT_MINUTES = 10;
    private static final long DEFAULT_EMPTY_RECHECK_MINUTES = 30;
    private static final String LEGACY_EMPTY_MESSAGE = "No partitions to register";
    private static final Pattern SERIES_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final DatalatheClient client;
    private final ExecutorService executor;
    private final long timeoutMinutes;
    private final long emptyRecheckMinutes;
    private final ConcurrentHashMap<String, Creation> inflight;
    private final SearchCoordinator searches;
    private final ConcurrentHashMap<String, Long> emptySince;
    private final SeriesNamer seriesNamer;

    LongSupplier clock = System::currentTimeMillis;

    public ChipResolver(DatalatheClient client) {
        this(client, Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors()),
                r -> {
                    Thread t = new Thread(r, "chip-resolver");
                    t.setDaemon(true);
                    return t;
                }), DEFAULT_TIMEOUT_MINUTES);
    }

    public ChipResolver(DatalatheClient client, ExecutorService executor, long timeoutMinutes) {
        this(client, executor, timeoutMinutes, DEFAULT_EMPTY_RECHECK_MINUTES);
    }

    /**
     * @param emptyRecheckMinutes how long a create that failed because the
     *                            source was empty is remembered before the
     *                            resolver retries it; 0 disables the cache
     */
    public ChipResolver(DatalatheClient client, ExecutorService executor, long timeoutMinutes,
                        long emptyRecheckMinutes) {
        this.client = client;
        this.executor = executor;
        this.timeoutMinutes = timeoutMinutes;
        this.emptyRecheckMinutes = emptyRecheckMinutes;
        this.inflight = new ConcurrentHashMap<>();
        this.searches = new SearchCoordinator();
        this.emptySince = new ConcurrentHashMap<>();
        this.seriesNamer = null;
    }

    private ChipResolver(ChipResolver base, DatalatheClient client) {
        this(base, client, base.seriesNamer);
    }

    private ChipResolver(ChipResolver base, DatalatheClient client, SeriesNamer seriesNamer) {
        this.seriesNamer = seriesNamer;
        this.client = client;
        this.executor = base.executor;
        this.timeoutMinutes = base.timeoutMinutes;
        this.emptyRecheckMinutes = base.emptyRecheckMinutes;
        this.inflight = base.inflight;
        this.searches = base.searches;
        this.emptySince = base.emptySince;
        this.clock = base.clock;
    }

    /**
     * Returns a resolver whose requests carry {@code correlationId} (see
     * {@link DatalatheClient#withCorrelationId}) and that shares this resolver's
     * executor, in-flight creates, in-flight searches and empty-source cache.
     * Use it instead of a second {@code ChipResolver}: two resolvers built from
     * the same client do not see each other's in-flight creates and stage the
     * same chip twice.
     *
     * <p>When two scoped resolvers race for the same chip, one create runs and
     * both wait on it, so the chip carries the ID of whichever started it.
     * A shared chip search likewise carries the ID of the resolve that sent it.
     * A null, blank or invalid ID returns this resolver.</p>
     */
    public ChipResolver withCorrelationId(String correlationId) {
        DatalatheClient scoped = client.withCorrelationId(correlationId);
        return scoped == client ? this : new ChipResolver(this, scoped);
    }

    /**
     * Returns a resolver that publishes every chip it creates into the series
     * that {@code namer} returns for the chip's isolation tag, and that shares
     * this resolver's executor, in-flight creates, in-flight searches and
     * empty-source cache.
     *
     * <p>A series holds one current chip per table and partition, so
     * {@code series:<id>@latest} reads the tag's whole data set and the series
     * can be browsed without knowing chip IDs. Each partition of a table must
     * be its own chip partition: two chips with the same table and partition
     * value in one series replace each other.</p>
     *
     * <p>A null or invalid series ID leaves that chip out of any series; it
     * never fails the create. The engine needs a chip manager.</p>
     */
    public ChipResolver withSeries(SeriesNamer namer) {
        return new ChipResolver(this, client, namer);
    }

    private SeriesRef seriesFor(String tagKey, String tagValue) {
        if (seriesNamer == null) {
            return null;
        }
        String id = seriesNamer.seriesId(tagKey, tagValue);
        if (id == null) {
            return null;
        }
        if (!SERIES_ID.matcher(id).matches()) {
            log.warn("Series id '{}' for tag {}={} is not valid; creating the chip outside any series",
                    id, tagKey, tagValue);
            return null;
        }
        return SeriesRef.of(id);
    }

    public long getEmptyRecheckMinutes() {
        return emptyRecheckMinutes;
    }

    /**
     * Resolves chips from SQL queries. Extracts table names via
     * {@code extractTables()}, then resolves.
     *
     * @param reportQueries   SQL queries to parse for table names
     * @param partitionValues partition values for partitioned tables (e.g. months)
     * @param tagKey          tag key for tenant isolation (used in search and tagging)
     * @param tagValue        tag value for tenant isolation
     * @param factory         strategy for classifying tables and building sources
     * @return resolved chips split into unpartitioned and partitioned
     * @throws IOException if table extraction or chip search fails
     */
    public ResolvedChips resolve(List<String> reportQueries,
                                 List<String> partitionValues,
                                 String tagKey, String tagValue,
                                 ChipFactory factory) throws IOException {
        return resolve(reportQueries, partitionValues, tagKey, tagValue, factory, false);
    }

    /**
     * Resolves chips from SQL queries with optional MySQL-to-DuckDB transform.
     *
     * @param reportQueries   SQL queries to parse for table names
     * @param partitionValues partition values for partitioned tables (e.g. months)
     * @param tagKey          tag key for tenant isolation
     * @param tagValue        tag value for tenant isolation
     * @param factory         strategy for classifying tables and building sources
     * @param transform       when true, transforms MySQL/MariaDB syntax before extracting tables
     * @return resolved chips split into unpartitioned and partitioned
     * @throws IOException if table extraction or chip search fails
     */
    public ResolvedChips resolve(List<String> reportQueries,
                                 List<String> partitionValues,
                                 String tagKey, String tagValue,
                                 ChipFactory factory,
                                 boolean transform) throws IOException {
        Set<String> tables = new HashSet<>();
        for (String query : reportQueries) {
            if (transform) {
                tables.addAll(client.extractTablesWithTransform(query, true).getTables());
            } else {
                tables.addAll(client.extractTables(query));
            }
        }
        return resolveForTables(tables, partitionValues, tagKey, tagValue, factory);
    }

    /**
     * Resolves chips for known table names.
     *
     * @param tables          the table names that the report requires
     * @param partitionValues partition values for partitioned tables (e.g. months)
     * @param tagKey          tag key for tenant isolation
     * @param tagValue        tag value for tenant isolation
     * @param factory         strategy for classifying tables and building sources
     * @return resolved chips split into unpartitioned and partitioned
     * @throws IOException if chip search fails
     */
    public ResolvedChips resolveForTables(Set<String> tables,
                                          List<String> partitionValues,
                                          String tagKey, String tagValue,
                                          ChipFactory factory) throws IOException {

        long startNanos = System.nanoTime();

        Set<String> pvSet = new HashSet<>();
        for (Object pv : partitionValues) {
            pvSet.add(String.valueOf(pv));
        }

        // Classify tables via factory; expected freshness is per table for
        // unpartitioned tables (keyed "table|null") and per table/partition
        // pair for partitioned ones (keyed "table|pv")
        Set<String> partitionedTables = new HashSet<>();
        Set<String> unpartitionedTables = new HashSet<>();
        Map<String, Map<String, String>> freshnessByKey = new HashMap<>();
        Map<String, Map<String, String>> additionalByKey = new HashMap<>();
        for (String table : tables) {
            if (factory.isPartitioned(table)) {
                partitionedTables.add(table);
                for (String pv : pvSet) {
                    putFreshness(freshnessByKey, table, pv, tagKey,
                            factory.freshnessTags(table, pv));
                    putAdditional(additionalByKey, freshnessByKey, table, pv, tagKey,
                            factory.additionalTags(table, pv));
                }
            } else {
                unpartitionedTables.add(table);
                putFreshness(freshnessByKey, table, null, tagKey,
                        factory.freshnessTags(table, null));
                putAdditional(additionalByKey, freshnessByKey, table, null, tagKey,
                        factory.additionalTags(table, null));
            }
        }

        String tag = tagKey + ":" + tagValue;
        String keyPrefix = tag + "|";

        List<String> existingUnpartitionedIds = new ArrayList<>();
        List<String> existingPartitionedIds = new ArrayList<>();
        List<Pending> unpartitionedPending = new ArrayList<>();
        List<Pending> partitionedPending = new ArrayList<>();
        int evicted = 0;

        long searchStart = System.nanoTime();
        SearchTicket ticket = search(tag, tagKey, tagValue);
        long searchNanos = System.nanoTime() - searchStart;
        try {
            SearchChipsResponse existing = ticket.response;
            Set<String> hiddenChipIds = searches.evictedSince(tag, ticket.search);

            // Index existing chips by table and (table|pv)
            Set<String> existingUnpartitionedTables = new HashSet<>();
            Set<String> existingPartitionedKeys = new HashSet<>();

            Map<String, Map<String, String>> tagsByChip = new HashMap<>();
            if (existing.getTags() != null) {
                for (var chipTag : existing.getTags()) {
                    tagsByChip.computeIfAbsent(chipTag.getChipId(), k -> new HashMap<>())
                            .put(chipTag.getKey(), chipTag.getValue());
                }
            }
            Set<String> evictedChipIds = new HashSet<>();

            if (existing.getChips() != null) {
                for (var chip : existing.getChips()) {
                    String table = chip.getTableName();
                    String chipId = chip.getChipId();

                    if (unpartitionedTables.contains(table)
                            && chipId.equals(chip.getSubChipId())) {
                        if (hiddenChipIds.contains(chipId)) {
                            continue;
                        }
                        String key = keyPrefix + table + "|" + null;
                        boolean alreadyEvicted = evictedChipIds.contains(chipId);
                        if (evictIfStale(chip, freshnessByKey.get(table + "|" + null),
                                tagsByChip, evictedChipIds)) {
                            if (!alreadyEvicted) {
                                noteEviction(tag, key, chipId);
                                evicted++;
                            }
                            continue;
                        }
                        if (existingUnpartitionedTables.add(table)) {
                            existingUnpartitionedIds.add(chipId);
                            noteSighting(tag, key);
                        }
                    } else if (partitionedTables.contains(table)
                            && pvSet.contains(chip.getPartitionValue())) {
                        if (hiddenChipIds.contains(chipId)) {
                            continue;
                        }
                        String key = keyPrefix + table + "|" + chip.getPartitionValue();
                        boolean alreadyEvicted = evictedChipIds.contains(chipId);
                        if (evictIfStale(chip, freshnessByKey.get(table + "|" + chip.getPartitionValue()),
                                tagsByChip, evictedChipIds)) {
                            if (!alreadyEvicted) {
                                noteEviction(tag, key, chipId);
                                evicted++;
                            }
                            continue;
                        }
                        if (existingPartitionedKeys.add(table + "|" + chip.getPartitionValue())) {
                            existingPartitionedIds.add(chipId);
                            noteSighting(tag, key);
                        }
                    }
                }
            }

            // Create missing chips in parallel with dedup gate
            for (String table : unpartitionedTables) {
                if (!existingUnpartitionedTables.contains(table)) {
                    unpartitionedPending.add(getOrCreate(table, null, tagKey, tagValue, factory,
                            freshnessByKey.get(table + "|" + null),
                            additionalByKey.get(table + "|" + null)));
                }
            }
            for (String pv : pvSet) {
                for (String table : partitionedTables) {
                    if (!existingPartitionedKeys.contains(table + "|" + pv)) {
                        partitionedPending.add(getOrCreate(table, pv, tagKey, tagValue, factory,
                                freshnessByKey.get(table + "|" + pv),
                                additionalByKey.get(table + "|" + pv)));
                    }
                }
            }
        } finally {
            searches.end(tag, ticket.search);
        }

        int found = existingUnpartitionedIds.size() + existingPartitionedIds.size();

        List<Pending> pending = new ArrayList<>();
        for (Pending p : unpartitionedPending) {
            if (p != null) pending.add(p);
        }
        for (Pending p : partitionedPending) {
            if (p != null) pending.add(p);
        }
        if (pending.isEmpty()) {
            return new ResolvedChips(existingUnpartitionedIds, existingPartitionedIds,
                    new ResolveStats(found, 0, 0, evicted, ticket.shared, millis(searchNanos), 0, 0,
                            millis(System.nanoTime() - startNanos)));
        }

        long waitStart = System.nanoTime();
        CompletableFuture<Long> awaitedDone = CompletableFuture
                .allOf(pending.stream().filter(p -> !p.started)
                        .map(p -> p.creation.future).toArray(CompletableFuture[]::new))
                .handle((v, ex) -> System.nanoTime());
        CompletableFuture.allOf(pending.stream()
                .map(p -> p.creation.future).toArray(CompletableFuture[]::new)).join();

        // Collect results
        int created = 0;
        int awaited = 0;
        long createNanos = 0;
        List<String> unpartitionedIds = new ArrayList<>(existingUnpartitionedIds);
        List<String> partitionedIds = new ArrayList<>(existingPartitionedIds);
        for (Pending p : pending) {
            String id = p.creation.future.join();
            if (p.started) {
                createNanos += p.creation.nanos;
            }
            if (id == null) {
                continue;
            }
            if (p.started) {
                created++;
            } else {
                awaited++;
            }
            (p.partitioned ? partitionedIds : unpartitionedIds).add(id);
        }
        long waitNanos = awaited == 0 ? 0 : Math.max(0, awaitedDone.join() - waitStart);

        return new ResolvedChips(unpartitionedIds, partitionedIds,
                new ResolveStats(found, created, awaited, evicted, ticket.shared, millis(searchNanos),
                        millis(createNanos), millis(waitNanos), millis(System.nanoTime() - startNanos)));
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    /**
     * Returns the chip search for the tag: a search already in flight when no
     * create or eviction for the tag has finished since it started, otherwise
     * one sent here. A resolve whose shared search fails sends its own.
     */
    private SearchTicket search(String tag, String tagKey, String tagValue) throws IOException {
        boolean allowJoin = true;
        while (true) {
            SearchCoordinator.Claim claim = searches.begin(tag, allowJoin);
            SharedSearch search = claim.search;
            if (claim.owner) {
                try {
                    SearchChipsResponse response = client.searchChips(null, null, tagKey, tagValue);
                    searches.finish(tag, search);
                    search.future.complete(response);
                    return new SearchTicket(search, response, false);
                } catch (Throwable t) {
                    searches.finish(tag, search);
                    search.future.completeExceptionally(t);
                    searches.end(tag, search);
                    throw t;
                }
            }
            try {
                return new SearchTicket(search, search.future.join(), true);
            } catch (CompletionException | CancellationException e) {
                searches.end(tag, search);
                allowJoin = false;
            }
        }
    }

    private void noteSighting(String tag, String key) {
        emptySince.remove(key);
        inflight.computeIfPresent(key, (k, creation) ->
                creation.succeeded() && searches.settled(tag, creation.completedSequence) ? null : creation);
    }

    private void noteEviction(String tag, String key, String chipId) {
        searches.evicted(tag, chipId);
        inflight.computeIfPresent(key, (k, creation) ->
                creation.succeeded() && chipId.equals(creation.future.getNow(null)) ? null : creation);
    }

    private static void putAdditional(Map<String, Map<String, String>> additionalByKey,
                                      Map<String, Map<String, String>> freshnessByKey,
                                      String table, String partitionValue, String tagKey,
                                      Map<String, String> additional) {
        if (additional == null || additional.isEmpty()) {
            return;
        }
        if (additional.containsKey(tagKey)) {
            throw new IllegalArgumentException(
                    "Additional tag key '" + tagKey + "' for table '" + table
                            + "' collides with the tenant tag key");
        }
        Map<String, String> freshness = freshnessByKey.get(table + "|" + partitionValue);
        if (freshness != null) {
            for (String key : additional.keySet()) {
                if (freshness.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "Additional tag key '" + key + "' for table '" + table
                                    + "' collides with a freshness tag key");
                }
            }
        }
        additionalByKey.put(table + "|" + partitionValue, Map.copyOf(additional));
    }

    private static void putFreshness(Map<String, Map<String, String>> freshnessByKey,
                                     String table, String partitionValue, String tagKey,
                                     Map<String, String> freshness) {
        if (freshness == null || freshness.isEmpty()) {
            return;
        }
        if (freshness.containsKey(tagKey)) {
            throw new IllegalArgumentException(
                    "Freshness tag key '" + tagKey + "' for table '" + table
                            + "' collides with the tenant tag key");
        }
        freshnessByKey.put(table + "|" + partitionValue, Map.copyOf(freshness));
    }

    /**
     * Returns the create for the requested chip, or null when an empty source
     * is still remembered. If creation is already in-flight for the same key,
     * returns the existing one instead of starting a second.
     */
    private Pending getOrCreate(String table, String partitionValue,
                                String tagKey, String tagValue,
                                ChipFactory factory,
                                Map<String, String> freshnessTags,
                                Map<String, String> additionalTags) {

        String tag = tagKey + ":" + tagValue;
        String key = tag + "|" + table + "|" + partitionValue;

        if (emptySuppressed(key)) {
            return null;
        }

        Map<String, String> tags = new HashMap<>();
        if (additionalTags != null) {
            tags.putAll(additionalTags);
        }
        tags.put(tagKey, tagValue);
        if (freshnessTags != null) {
            tags.putAll(freshnessTags);
        }

        SeriesRef series = seriesFor(tagKey, tagValue);

        AtomicBoolean started = new AtomicBoolean();
        Creation creation = inflight.computeIfAbsent(key, k -> {
            started.set(true);
            log.info("Creating chip for table={} partition={}", table, partitionValue);

            Creation c = new Creation();
            c.future = CompletableFuture
                    .supplyAsync(() -> {
                        long createStart = System.nanoTime();
                        try {
                            ChipSource source;
                            try {
                                source = factory.buildSource(table, partitionValue);
                            } catch (RuntimeException e) {
                                throw contextualize("ChipFactory.buildSource", table, partitionValue, e);
                            }
                            logBuiltSource(table, partitionValue, source);
                            try {
                                String id = series == null
                                        ? client.createChip(source, null, tags)
                                        : client.createChip(source, null, tags, series);
                                emptySince.remove(key);
                                if (id != null) {
                                    c.completedSequence = searches.changed(tag);
                                }
                                return id;
                            } catch (IOException e) {
                                handleCreateFailure(key, table, partitionValue, e);
                                return null;
                            } catch (RuntimeException e) {
                                throw contextualize("createChip", table, partitionValue, e);
                            }
                        } finally {
                            c.nanos = System.nanoTime() - createStart;
                        }
                    }, executor)
                    .orTimeout(timeoutMinutes, TimeUnit.MINUTES);
            return c;
        });
        if (started.get()) {
            creation.future.whenComplete((id, ex) -> {
                if (id == null || ex != null) inflight.remove(key, creation);
            });
        }
        return new Pending(creation, started.get(), partitionValue != null);
    }

    /**
     * Deletes the chip when its tags don't carry every expected freshness
     * entry. Returns true when the chip should be treated as missing (deleted
     * here, already deleted concurrently, or evicted earlier in this pass).
     * A failed delete is followed by a lookup: a chip that is already gone
     * counts as evicted, while one that is still present stays in play —
     * serving stale data beats creating a duplicate alongside a chip that
     * wouldn't die.
     */
    private boolean evictIfStale(SearchChipsResponse.ChipRecord chip,
                                 Map<String, String> expected,
                                 Map<String, Map<String, String>> tagsByChip,
                                 Set<String> evictedChipIds) {
        if (expected == null) {
            return false;
        }
        String chipId = chip.getChipId();
        if (evictedChipIds.contains(chipId)) {
            return true;
        }
        Map<String, String> chipTags = tagsByChip.getOrDefault(chipId, Map.of());
        boolean stale = false;
        for (var entry : expected.entrySet()) {
            if (!entry.getValue().equals(chipTags.get(entry.getKey()))) {
                stale = true;
                break;
            }
        }
        if (!stale) {
            return false;
        }
        try {
            client.deleteChip(chipId);
            log.info("Evicted stale chip {} for table={} partition={} (freshness tags changed)",
                    chipId, chip.getTableName(), chip.getPartitionValue());
        } catch (ChipNotFoundException e) {
            log.info("Stale chip {} for table={} already deleted concurrently",
                    chipId, chip.getTableName());
        } catch (IOException e) {
            if (!chipGone(chipId)) {
                log.warn("Failed to evict stale chip {} for table={}; keeping it this resolve",
                        chipId, chip.getTableName(), e);
                return false;
            }
            log.info("Stale chip {} for table={} already deleted concurrently (delete failed, lookup confirms gone)",
                    chipId, chip.getTableName());
        }
        evictedChipIds.add(chipId);
        return true;
    }

    /**
     * Engines before 1.16.0 answer a delete of a missing chip with a 500 rather
     * than a 404, so a lost eviction race looks like a real failure. A lookup
     * settles it.
     */
    private boolean chipGone(String chipId) {
        try {
            client.getChip(chipId);
            return false;
        } catch (ChipNotFoundException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static IllegalStateException contextualize(String stage, String table, String partitionValue,
                                                        RuntimeException e) {
        log.error("{} threw for table={} partition={}", stage, table, partitionValue, e);
        return new IllegalStateException(
                stage + " failed for table=" + table + " partition=" + partitionValue, e);
    }

    private static void logBuiltSource(String table, String partitionValue, ChipSource source) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (source == null) {
            log.debug("ChipFactory.buildSource returned null for table={} partition={}", table, partitionValue);
            return;
        }
        ChipSource.Partition partition = source.getPartition();
        log.debug("Built chip source for table={} partition={} sourceType={} database={} sourceTable={} "
                        + "partitionBy={} partitionValues={} filePath={} s3Path={} sourceChipIds={}",
                table, partitionValue, source.getSourceType(), source.getDatabaseName(), source.getTableName(),
                partition == null ? null : partition.getPartitionBy(),
                partition == null ? null : partition.getPartitionValues(),
                source.getFilePath(), source.getS3Path(), source.getSourceChipIds());
        if (log.isTraceEnabled()) {
            log.trace("Chip source query for table={} partition={} query={} partitionQuery={}",
                    table, partitionValue, source.getQuery(),
                    partition == null ? null : partition.getPartitionQuery());
        }
    }

    private void handleCreateFailure(String key, String table, String partitionValue, IOException e) {
        if (isEmptySource(e)) {
            if (emptyRecheckMinutes > 0) {
                emptySince.put(key, clock.getAsLong());
            }
            String message = e instanceof DatalatheApiException api && api.getServerMessage() != null
                    ? api.getServerMessage() : e.getMessage();
            log.info("Chip source empty for table={} partition={} message={}",
                    table, partitionValue, message);
        } else if (e instanceof DatalatheApiException api) {
            log.warn("Chip creation failed for table={} partition={} errorCode={} message={}",
                    table, partitionValue, api.getErrorCode(), api.getServerMessage());
        } else {
            log.error("Chip creation failed for table={} partition={}", table, partitionValue, e);
        }
    }

    /**
     * The message match covers engines that predate the {@code EMPTY_SOURCE}
     * error code.
     */
    private static boolean isEmptySource(IOException e) {
        if (e instanceof DatalatheApiException api && "EMPTY_SOURCE".equals(api.getErrorCode())) {
            return true;
        }
        return e.getMessage() != null && e.getMessage().contains(LEGACY_EMPTY_MESSAGE);
    }

    private boolean emptySuppressed(String key) {
        if (emptyRecheckMinutes <= 0) {
            return false;
        }
        Long since = emptySince.get(key);
        if (since == null) {
            return false;
        }
        if (clock.getAsLong() - since >= TimeUnit.MINUTES.toMillis(emptyRecheckMinutes)) {
            emptySince.remove(key, since);
            return false;
        }
        return true;
    }

    /** Returns the number of currently in-flight chip creations. */
    public int inflightCount() {
        return inflight.size();
    }

    private static final class Creation {
        CompletableFuture<String> future;
        volatile long completedSequence;
        volatile long nanos;

        boolean succeeded() {
            return future.isDone() && !future.isCompletedExceptionally();
        }
    }

    private static final class Pending {
        final Creation creation;
        final boolean started;
        final boolean partitioned;

        Pending(Creation creation, boolean started, boolean partitioned) {
            this.creation = creation;
            this.started = started;
            this.partitioned = partitioned;
        }
    }

    private static final class SharedSearch {
        final long sequence;
        final CompletableFuture<SearchChipsResponse> future = new CompletableFuture<>();

        SharedSearch(long sequence) {
            this.sequence = sequence;
        }
    }

    private static final class SearchTicket {
        final SharedSearch search;
        final SearchChipsResponse response;
        final boolean shared;

        SearchTicket(SharedSearch search, SearchChipsResponse response, boolean shared) {
            this.search = search;
            this.response = response;
            this.shared = shared;
        }
    }

    /**
     * Orders this resolver's searches against its own creates and evictions,
     * per tag. Every create or eviction takes the next sequence number and a
     * search carries the number of the last change that finished before it
     * started, so a search is known to reflect a change exactly when its
     * number is not lower than the change's.
     */
    private static final class SearchCoordinator {

        static final class Claim {
            final SharedSearch search;
            final boolean owner;

            Claim(SharedSearch search, boolean owner) {
                this.search = search;
                this.owner = owner;
            }
        }

        private static final class TagState {
            long changeSequence;
            SharedSearch current;
            final TreeMap<Long, Integer> active = new TreeMap<>();
            final Map<String, Long> evicted = new HashMap<>();

            TagState(long changeSequence) {
                this.changeSequence = changeSequence;
            }
        }

        private final Map<String, TagState> tags = new HashMap<>();
        private long sequence;

        synchronized Claim begin(String tag, boolean allowJoin) {
            TagState state = tags.get(tag);
            if (state == null) {
                state = new TagState(++sequence);
                tags.put(tag, state);
            }
            boolean owner = !allowJoin
                    || state.current == null
                    || state.current.sequence != state.changeSequence;
            if (owner) {
                state.current = new SharedSearch(state.changeSequence);
            }
            state.active.merge(state.current.sequence, 1, Integer::sum);
            return new Claim(state.current, owner);
        }

        synchronized void finish(String tag, SharedSearch search) {
            TagState state = tags.get(tag);
            if (state != null && state.current == search) {
                state.current = null;
            }
        }

        synchronized void end(String tag, SharedSearch search) {
            TagState state = tags.get(tag);
            if (state == null) {
                return;
            }
            state.active.computeIfPresent(search.sequence, (k, count) -> count == 1 ? null : count - 1);
            if (state.active.isEmpty()) {
                state.evicted.clear();
                if (state.current == null) {
                    tags.remove(tag);
                }
            } else {
                long oldest = state.active.firstKey();
                state.evicted.values().removeIf(at -> at <= oldest);
            }
        }

        synchronized long changed(String tag) {
            long at = ++sequence;
            TagState state = tags.get(tag);
            if (state != null) {
                state.changeSequence = at;
            }
            return at;
        }

        synchronized void evicted(String tag, String chipId) {
            long at = changed(tag);
            TagState state = tags.get(tag);
            if (state != null) {
                state.evicted.put(chipId, at);
            }
        }

        /** Chips evicted after the search started, which it may still list. */
        synchronized Set<String> evictedSince(String tag, SharedSearch search) {
            TagState state = tags.get(tag);
            if (state == null || state.evicted.isEmpty()) {
                return Set.of();
            }
            Set<String> ids = new HashSet<>();
            for (var entry : state.evicted.entrySet()) {
                if (search.sequence < entry.getValue()) {
                    ids.add(entry.getKey());
                }
            }
            return ids;
        }

        /** Whether every search still in use started after the change. */
        synchronized boolean settled(String tag, long changeSequence) {
            TagState state = tags.get(tag);
            return state == null || state.active.isEmpty() || state.active.firstKey() >= changeSequence;
        }
    }
}
