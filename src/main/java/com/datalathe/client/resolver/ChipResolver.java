package com.datalathe.client.resolver;

import com.datalathe.client.ChipNotFoundException;
import com.datalathe.client.DatalatheApiException;
import com.datalathe.client.DatalatheClient;
import com.datalathe.client.SearchChipsResponse;
import com.datalathe.client.types.ChipSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * Resolves the set of chips needed for a report, creating any that are missing.
 *
 * <p>Given a set of table names (or SQL queries to parse), partition values, and
 * a tag for tenant isolation, the resolver:</p>
 * <ol>
 *   <li>Searches the engine for existing chips matching the tag</li>
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

    private final DatalatheClient client;
    private final ExecutorService executor;
    private final long timeoutMinutes;
    private final long emptyRecheckMinutes;
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> emptySince = new ConcurrentHashMap<>();

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

        // Classify tables via factory
        Set<String> partitionedTables = new HashSet<>();
        Set<String> unpartitionedTables = new HashSet<>();
        Map<String, Map<String, String>> freshnessByTable = new HashMap<>();
        for (String table : tables) {
            if (factory.isPartitioned(table)) {
                partitionedTables.add(table);
            } else {
                unpartitionedTables.add(table);
            }
            Map<String, String> freshness = factory.freshnessTags(table);
            if (freshness != null && !freshness.isEmpty()) {
                if (freshness.containsKey(tagKey)) {
                    throw new IllegalArgumentException(
                            "Freshness tag key '" + tagKey + "' for table '" + table
                                    + "' collides with the tenant tag key");
                }
                freshnessByTable.put(table, Map.copyOf(freshness));
            }
        }

        // Search existing chips by tag
        SearchChipsResponse existing = client.searchChips(null, null, tagKey, tagValue);

        // Index existing chips by table and (table|pv)
        Set<String> existingUnpartitionedTables = new HashSet<>();
        Set<String> existingPartitionedKeys = new HashSet<>();
        List<String> existingUnpartitionedIds = new ArrayList<>();
        List<String> existingPartitionedIds = new ArrayList<>();
        Set<String> pvSet = new HashSet<>();
        for (Object pv : partitionValues) {
            pvSet.add(String.valueOf(pv));
        }
        String keyPrefix = tagKey + ":" + tagValue + "|";

        Map<String, Map<String, String>> tagsByChip = new HashMap<>();
        if (existing.getTags() != null) {
            for (var tag : existing.getTags()) {
                tagsByChip.computeIfAbsent(tag.getChipId(), k -> new HashMap<>())
                        .put(tag.getKey(), tag.getValue());
            }
        }
        Set<String> evictedChipIds = new HashSet<>();

        if (existing.getChips() != null) {
            for (var chip : existing.getChips()) {
                String table = chip.getTableName();

                if (unpartitionedTables.contains(table)
                        && chip.getChipId().equals(chip.getSubChipId())) {
                    if (evictIfStale(chip, freshnessByTable.get(table), tagsByChip, evictedChipIds)) {
                        continue;
                    }
                    if (existingUnpartitionedTables.add(table)) {
                        existingUnpartitionedIds.add(chip.getChipId());
                        // Chip is now searchable — evict from inflight and empty caches
                        String key = keyPrefix + table + "|" + null;
                        inflight.remove(key);
                        emptySince.remove(key);
                    }
                } else if (partitionedTables.contains(table)
                        && pvSet.contains(chip.getPartitionValue())) {
                    if (evictIfStale(chip, freshnessByTable.get(table), tagsByChip, evictedChipIds)) {
                        continue;
                    }
                    if (existingPartitionedKeys.add(table + "|" + chip.getPartitionValue())) {
                        existingPartitionedIds.add(chip.getChipId());
                        String key = keyPrefix + table + "|" + chip.getPartitionValue();
                        inflight.remove(key);
                        emptySince.remove(key);
                    }
                }
            }
        }

        // Find missing unpartitioned tables
        List<String> missingUnpartitioned = new ArrayList<>();
        for (String table : unpartitionedTables) {
            if (!existingUnpartitionedTables.contains(table)) {
                missingUnpartitioned.add(table);
            }
        }

        // Find missing partitioned (table, pv) pairs
        record PartitionGap(String table, String partitionValue) {}
        List<PartitionGap> missingPartitioned = new ArrayList<>();
        for (String pv : pvSet) {
            for (String table : partitionedTables) {
                if (!existingPartitionedKeys.contains(table + "|" + pv)) {
                    missingPartitioned.add(new PartitionGap(table, pv));
                }
            }
        }

        if (missingUnpartitioned.isEmpty() && missingPartitioned.isEmpty()) {
            return new ResolvedChips(existingUnpartitionedIds, existingPartitionedIds);
        }

        // Create missing chips in parallel with dedup gate
        List<CompletableFuture<String>> unpartitionedFutures = new ArrayList<>();
        for (String table : missingUnpartitioned) {
            unpartitionedFutures.add(getOrCreate(table, null, tagKey, tagValue, factory,
                    freshnessByTable.get(table)));
        }

        List<CompletableFuture<String>> partitionedFutures = new ArrayList<>();
        for (PartitionGap gap : missingPartitioned) {
            partitionedFutures.add(getOrCreate(gap.table(), gap.partitionValue(), tagKey, tagValue, factory,
                    freshnessByTable.get(gap.table())));
        }

        List<CompletableFuture<?>> allFutures = new ArrayList<>();
        allFutures.addAll(unpartitionedFutures);
        allFutures.addAll(partitionedFutures);
        CompletableFuture.allOf(allFutures.toArray(CompletableFuture[]::new)).join();

        // Collect results
        List<String> unpartitionedIds = new ArrayList<>(existingUnpartitionedIds);
        for (var f : unpartitionedFutures) {
            String id = f.join();
            if (id != null) unpartitionedIds.add(id);
        }

        List<String> partitionedIds = new ArrayList<>(existingPartitionedIds);
        for (var f : partitionedFutures) {
            String id = f.join();
            if (id != null) partitionedIds.add(id);
        }

        return new ResolvedChips(unpartitionedIds, partitionedIds);
    }

    /**
     * Returns a future for the requested chip. If creation is already in-flight
     * for the same key, returns the existing future instead of starting a second.
     */
    private CompletableFuture<String> getOrCreate(String table, String partitionValue,
                                                   String tagKey, String tagValue,
                                                   ChipFactory factory,
                                                   Map<String, String> freshnessTags) {

        String key = tagKey + ":" + tagValue + "|" + table + "|" + partitionValue;

        if (emptySuppressed(key)) {
            return CompletableFuture.completedFuture(null);
        }

        Map<String, String> tags = new HashMap<>();
        tags.put(tagKey, tagValue);
        if (freshnessTags != null) {
            tags.putAll(freshnessTags);
        }

        return inflight.computeIfAbsent(key, k -> {
            log.info("Creating chip for table={} partition={}", table, partitionValue);

            return CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            ChipSource source = factory.buildSource(table, partitionValue);
                            String id = client.createChip(source, null, tags);
                            emptySince.remove(key);
                            return id;
                        } catch (IOException e) {
                            handleCreateFailure(key, table, partitionValue, e);
                            return null;
                        }
                    }, executor)
                    .orTimeout(timeoutMinutes, TimeUnit.MINUTES)
                    .whenComplete((id, ex) -> {
                        if (id == null || ex != null) inflight.remove(key);
                    });
        });
    }

    /**
     * Deletes the chip when its tags don't carry every expected freshness
     * entry. Returns true when the chip should be treated as missing (deleted
     * here, already deleted concurrently, or evicted earlier in this pass).
     * A failed delete keeps the stale chip in play — serving stale data beats
     * creating a duplicate alongside a chip that wouldn't die.
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
            log.warn("Failed to evict stale chip {} for table={}; keeping it this resolve",
                    chipId, chip.getTableName(), e);
            return false;
        }
        evictedChipIds.add(chipId);
        return true;
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
}
