package com.datalathe.client.resolver;

/**
 * What one {@link ChipResolver#resolve} or {@link ChipResolver#resolveForTables}
 * call did, returned by {@link ResolvedChips#stats()}.
 *
 * <p>Each resolve call gets its own instance, including calls made through a
 * {@link ChipResolver#withCorrelationId} or {@link ChipResolver#withSeries}
 * view, so a caller can log how many chips one request built and how long it
 * waited without counting calls on the client.</p>
 */
public final class ResolveStats {

    /** Stats with every count and time at zero. */
    public static final ResolveStats NONE = new ResolveStats(0, 0, 0, 0, false, 0, 0, 0, 0);

    private final int chipsFound;
    private final int chipsCreated;
    private final int chipsAwaited;
    private final int chipsEvicted;
    private final boolean searchShared;
    private final long searchMillis;
    private final long createMillis;
    private final long waitMillis;
    private final long totalMillis;

    ResolveStats(int chipsFound, int chipsCreated, int chipsAwaited, int chipsEvicted,
                        boolean searchShared, long searchMillis, long createMillis, long waitMillis,
                        long totalMillis) {
        this.chipsFound = chipsFound;
        this.chipsCreated = chipsCreated;
        this.chipsAwaited = chipsAwaited;
        this.chipsEvicted = chipsEvicted;
        this.searchShared = searchShared;
        this.searchMillis = searchMillis;
        this.createMillis = createMillis;
        this.waitMillis = waitMillis;
        this.totalMillis = totalMillis;
    }

    /** Chips that the search found. */
    public int chipsFound() {
        return chipsFound;
    }

    /** Chips that this call created. A create that returned no chip is not counted. */
    public int chipsCreated() {
        return chipsCreated;
    }

    /**
     * Chips that this call took from a create that another call on the same
     * resolver started, whether that create was still running or had finished
     * and no search had listed the chip yet.
     */
    public int chipsAwaited() {
        return chipsAwaited;
    }

    /**
     * Stale chips that this call deleted, or found already deleted, before
     * replacing them. A call that waited while another call on the same
     * resolver evicted a chip doesn't count that chip.
     */
    public int chipsEvicted() {
        return chipsEvicted;
    }

    /**
     * Whether this call used a chip search that another call on the same
     * resolver had started, instead of sending its own.
     */
    public boolean searchShared() {
        return searchShared;
    }

    /** Milliseconds this call waited for the chip search, its own or a shared one. */
    public long searchMillis() {
        return searchMillis;
    }

    /**
     * Milliseconds spent in the creates that this call started, summed over
     * the creates and including creates that returned no chip. Creates run in
     * parallel, so the sum can exceed {@link #totalMillis()}.
     */
    public long createMillis() {
        return createMillis;
    }

    /**
     * Wall-clock milliseconds this call waited for creates and evictions that
     * other calls started. The wait for creates runs from the moment the call
     * began waiting until the last of them finished, and is zero when the
     * {@link #chipsAwaited()} creates had all finished already. The wait for
     * each eviction of a stale chip that another call was running is added.
     */
    public long waitMillis() {
        return waitMillis;
    }

    /** Wall-clock milliseconds from the start of the resolve to its return, after table extraction. */
    public long totalMillis() {
        return totalMillis;
    }

    @Override
    public String toString() {
        return "ResolveStats{chipsFound=" + chipsFound
                + ", chipsCreated=" + chipsCreated
                + ", chipsAwaited=" + chipsAwaited
                + ", chipsEvicted=" + chipsEvicted
                + ", searchShared=" + searchShared
                + ", searchMillis=" + searchMillis
                + ", createMillis=" + createMillis
                + ", waitMillis=" + waitMillis
                + ", totalMillis=" + totalMillis + "}";
    }
}
