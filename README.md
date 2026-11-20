# Datalathe Java Client

A Java client library for interacting with Datalathe, providing a JDBC-compatible interface for querying and managing data.

## Features

- JDBC-compatible `ResultSet` implementation
- Support for multiple data types (Int32, Int64, Float32, Float64, Utf8, Boolean)
- Batch query execution
- Data staging capabilities
- Chip resolution (`ChipResolver` finds or creates the chips a report needs)
- Async ingest job handles (`createChipAsync` returns an `IngestJobHandle`; interrupted jobs resume via `resumeIngestJob`)
- AI query support (`aiQuery` with typed `AiQueryRequest`/`AiQueryResponse`)
- Connection management (`listConnections`, `upsertConnection`, `testConnection`, `deleteConnection` with `ConnectionInfo`)
- Typed API errors (`DatalatheApiException` carries the HTTP status and the server's error code and message)
- Null value handling
- Type conversion and metadata support

## Installation

The library is published to [Maven Central](https://central.sonatype.com/artifact/com.datalathe/datalathe-client). Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.datalathe</groupId>
    <artifactId>datalathe-client</artifactId>
    <version>1.18.1</version>
</dependency>
```

## Usage

### Basic Query Example

```java
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.types.GenerateReportResponse;

DatalatheClient client = new DatalatheClient("http://localhost:3000");

// Create a chip from a source database query
String chipId = client.createChip("my_database", "SELECT * FROM users", "users");

// Execute multiple queries against the chip
List<String> queries = Arrays.asList(
    "SELECT name, age FROM users",
    "SELECT COUNT(*) FROM users");
Map<Integer, GenerateReportResponse.Result> results =
    client.generateReport(Arrays.asList(chipId), queries);

// Process each result as a JDBC-style ResultSet (keyed by query index)
ResultSet rs = results.get(0).getResultSet();
while (rs.next()) {
    String name = rs.getString("name");
    int age = rs.getInt("age");
    // Process data...
}
```

For large results, `generateReportStream(...)` returns a forward-only
`DatalatheStreamingResultSet` that streams rows incrementally instead of
buffering the whole result in memory. It holds the live HTTP connection, so
close it when done:

```java
try (DatalatheStreamingResultSet rs =
        client.generateReportStream(Arrays.asList(chipId), "SELECT name, age FROM users")) {
    while (rs.next()) {
        String name = rs.getString("name");
        // Process data...
    }
}
```

### Chip Resolution

`ChipResolver` automates the find-or-create chip workflow for reports. Given the
tables a report needs (or SQL queries to parse), partition values, and a tag for
tenant isolation, it searches for existing chips, creates only the missing ones
in parallel (deduplicating concurrent requests for the same chip), and tags new
chips so future runs find them. Create one resolver per application and share it
across threads.

You supply a `ChipFactory` that tells the resolver which tables are partitioned
(one chip per partition value, e.g. monthly snapshots) versus unpartitioned (one
chip total, e.g. reference data), and how to build the `ChipSource` for each
chip:

```java
import java.util.List;
import java.util.Set;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.resolver.ChipFactory;
import com.datalathe.client.resolver.ChipResolver;
import com.datalathe.client.resolver.ResolvedChips;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;

DatalatheClient client = new DatalatheClient("http://localhost:3000");
ChipResolver resolver = new ChipResolver(client);

Set<String> partitionedTables = Set.of("orders");

ChipFactory factory = new ChipFactory() {
    @Override
    public boolean isPartitioned(String table) {
        return partitionedTables.contains(table);
    }

    @Override
    public ChipSource buildSource(String table, String partitionValue) {
        String sql = "SELECT * FROM " + table
            + (partitionValue != null ? " WHERE month = '" + partitionValue + "'" : "");

        return ChipSource.builder()
            .sourceType(SourceType.MYSQL)
            .databaseName("prod_db")
            .tableName(table)
            .query(sql)
            .partition(partitionValue != null
                ? ChipSource.Partition.builder()
                    .partitionBy("month")
                    .partitionValues(List.of(partitionValue))
                    .build()
                : null)
            .build();
    }
};

// From SQL — table names are extracted automatically
ResolvedChips chips = resolver.resolve(
    List.of("SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id"),
    List.of("2026-01", "2026-02"),
    "tenant", "42",
    factory);

// Or from known table names
ResolvedChips fromTables = resolver.resolveForTables(
    Set.of("users", "orders"),
    List.of("2026-01", "2026-02"),
    "tenant", "42",
    factory);

// Pass the full set of chip IDs to a report
client.generateReport(chips.allChipIds(), List.of("SELECT month, sum(total) FROM orders GROUP BY month"));
```

`ResolvedChips` splits the results into `unpartitionedChipIds()` and
`partitionedChipIds()`; `allChipIds()` concatenates both and `size()` returns
the total count. `stats()` reports what the call did; see
[Resolve statistics](#resolve-statistics).

Resolution is incremental: the first run for a 13-month trend report creates all
chips, subsequent runs find them via search and create nothing, and when the
window slides forward a month only the single new chip per partitioned table is
created.

When a create fails because the source has no rows (the engine's `EMPTY_SOURCE`
error code, or the older "No partitions to register" failure on engines that
predate it), the resolver logs a single INFO line and remembers the outcome: it
skips re-creating that chip on subsequent resolves until a recheck window
elapses (30 minutes by default). The window is configurable through the full
constructor — `new ChipResolver(client, executor, timeoutMinutes,
emptyRecheckMinutes)` — and `0` disables the cache so every resolve retries.
A cached entry clears as soon as a create succeeds or a search finds a chip for
that key. Other API errors log one WARN line (table, partition, error code,
server message) without a stack trace; only transport-level or unexpected
failures log ERROR with the full stack.

A runtime exception thrown by your `ChipFactory.buildSource` (or by the create
call itself) is logged at ERROR with the table and partition, then rethrown
wrapped in an `IllegalStateException` whose message names them, so the
`CompletionException` that escapes `resolveForTables` identifies the offending
table. It fails the whole resolve, since a factory that cannot build a source
is a configuration bug rather than a transient engine condition. At DEBUG the
resolver also logs each built source (source type, database, table, partition
config, file or S3 path, source chip ids); the SQL itself is logged only at
TRACE.

#### Publishing chips into a series

A series is a named collection that holds the current chip for each table and
partition. When the resolver publishes each tenant's chips into a series, a
report can read the tenant's whole data set with one reference,
`series:1234@latest`, and the TUI can browse the series without chip IDs.

```java
import com.datalathe.client.resolver.SeriesNamer;

ChipResolver resolver = new ChipResolver(client)
    .withSeries(SeriesNamer.tagValue("tenant"));
```

`withSeries` returns a resolver that shares the original's executor and
in-flight creates. `SeriesNamer.tagValue("tenant")` names the series after the
value of the `tenant` tag and leaves chips with any other tag key out. Pass
your own `SeriesNamer` to build a different ID.

- Requires engine 1.24.0 or later with a chip manager.
- A series ID has 1 to 128 characters from `A-Z`, `a-z`, `0-9`, `.`, `_`, `:`,
  and `-`. An ID outside those rules leaves the chip out of any series and
  logs a warning. It never fails the create.
- Each partition of a table must be its own chip partition. Two chips with the
  same table and partition value in one series replace each other.
- For several unpartitioned chips of one table, such as one chip per date,
  call `createChip` with `SeriesRef.of(id, partition)`. The partition names
  the chip's slot, and the chip itself stays unpartitioned.
- To check a chip before it joins a series, create it with
  `SeriesRef.held(id)`, then call `client.publishToSeries(chipId)` when the
  check passes, or delete the chip when it fails. A deleted held chip leaves
  the series as it was.
- A subclass of `DatalatheClient` that overrides
  `createChip(ChipSource, String, Map)` must also override the four-argument
  overload, which the resolver calls when a series is set.

#### Freshness tags

Chips are snapshots of their source, so by default the resolver serves a found
chip forever. A factory can opt a table into staleness tracking by overriding
`freshnessTags(String table)` to return the expected tag entries — encode each
staleness dimension as its own entry (e.g. a schema version, a load-generation
date) and change the value when chips staged under the old value must be
rebuilt. The resolver stamps those tags on every chip it creates for the table
(atomically with creation, alongside the tenant tag) and, on each resolve,
deletes any existing chip whose tags are missing an entry or hold a different
value — the replacement is created in the same pass, so callers never see the
eviction, and a freshly created chip can never be immediately stale.

The partition-aware overload `freshnessTags(String table, String
partitionValue)` is called once per table/partition pair for partitioned
tables, letting each partition's chip carry its own expected values (e.g. a
per-date calc timestamp) so a value change evicts only that partition's chip.
Its default delegates to the one-argument form, giving every partition the
same table-level values; for unpartitioned tables `partitionValue` is `null`.

```java
ChipFactory factory = new ChipFactory() {
    // isPartitioned / buildSource as above...

    @Override
    public Map<String, String> freshnessTags(String table) {
        return Map.of("schema_version", "v3");
    }

    @Override
    public Map<String, String> freshnessTags(String table, String partitionValue) {
        if ("orders".equals(table) && partitionValue != null) {
            return Map.of(
                "schema_version", "v3",
                "calc_date", calcDateFor(partitionValue));
        }
        return freshnessTags(table);
    }
};
```

Both methods are called on every resolve, so return precomputed values —
don't query a database or compute anything expensive there. Dynamic values
(e.g. the current load generation's max date) belong in the factory's
constructor, computed once per request. Two caveats: a chip for the table
created by any other writer without these tags is treated as stale and
deleted, and eviction is at-least-once — a concurrent resolver may briefly
see a chip disappear mid-report and self-heal on its next resolve.

One resolver, including its `withCorrelationId` and `withSeries` views, runs
one eviction per chip at a time. A resolve that finds a chip stale while
another resolve is evicting it waits for that eviction and uses its outcome:
when the chip is gone, the resolve creates the replacement or waits for the
create in flight, and when the chip was kept, the resolve keeps it too. The
resolver sends one `DELETE` for the chip, and no resolve returns a chip that
the resolver has evicted or is evicting.

Two resolver instances, such as two app servers, can still delete the same
chip at once, and the engine can answer the losing delete with a `500`. When
a delete fails with anything other than a `404`, the resolver looks the chip
up. If the chip is gone, the resolve creates the replacement. If the chip is
still listed, or the lookup fails, the resolver looks again after 50, 100,
200, and 400 ms, because the other delete can still be in flight. A chip
that the last lookup still lists, or can't check, stays in use for that
resolve: the resolver doesn't create a replacement next to a stale chip
that it can't delete. A `404` on the delete means that the chip is already gone, and
the resolve creates the replacement at once.

#### Shared chip search

Resolves on one resolver that overlap in time and use the same tag key and
value share one chip search. An app that renders several report elements at
once, each with its own resolve, sends one search for the page instead of one
per element. You don't configure anything, and the views that
`withCorrelationId` and `withSeries` return share searches with the resolver
they came from.

A resolve joins a search that is in flight only when this resolver has not
created or evicted a chip for the tag since that search started. Otherwise it
sends a new search. The resolver keeps no search result after the search
returns, so a resolve that starts later sends its own search.

The following rules keep a shared search from changing what a resolve returns:

- A resolve always sees the chips that this resolver created. The resolver
  keeps a finished create until a search lists the chip and no resolve still
  holds a search that started before the create finished.
- A resolve doesn't return a chip that this resolver evicted or is evicting,
  even when its search still lists the chip. The resolve checks each chip
  when it reaches the chip in the search result, so an eviction that starts
  or finishes after the search returned is covered. The same check applies
  to a chip that the resolve takes from a finished create.
- When a shared search fails, each resolve that joined it sends one search of
  its own. Only the resolve that sent the failed search gets the error.
- A chip that another server registers is visible to the next search, as
  before. The result a resolve acts on is at most one search round trip old,
  which is the same bound as a search of its own. The difference is the
  window between the start of a search and the start of a resolve that joins
  it: the resolve can miss a chip that another server registered in that
  window, and can get a chip that another server deleted in it.

A shared search carries the correlation ID of the resolve that sent it.

#### Resolve statistics

`ResolvedChips.stats()` returns a `ResolveStats` for the call that produced
the chips, including calls on a `withCorrelationId` or `withSeries` view:

```java
ResolvedChips chips = resolver.withCorrelationId(runId)
    .resolveForTables(tables, partitionValues, "tenant", "42", factory);

ResolveStats stats = chips.stats();
log.info("resolve found={} created={} awaited={} evicted={} searchMs={} createMs={} waitMs={} totalMs={}",
    stats.chipsFound(), stats.chipsCreated(), stats.chipsAwaited(), stats.chipsEvicted(),
    stats.searchMillis(), stats.createMillis(), stats.waitMillis(), stats.totalMillis());
```

The following table describes each value:

| Method | Value |
|---|---|
| `chipsFound()` | Chips that the search found. |
| `chipsCreated()` | Chips that this call created. A create that returned no chip isn't counted. |
| `chipsAwaited()` | Chips that this call took from a create that another call started, whether that create was still running or had finished. |
| `chipsEvicted()` | Stale chips that this call deleted, or found already deleted, before it replaced them. A call that waited while another call evicted a chip doesn't count that chip. |
| `searchShared()` | `true` when this call used a search that another call sent. |
| `searchMillis()` | Time this call waited for the chip search, its own or a shared one. |
| `createMillis()` | Time spent in the creates that this call started, summed over the creates. Creates run in parallel, so the sum can exceed `totalMillis()`. |
| `waitMillis()` | Wall-clock time this call waited for creates and evictions that other calls started. |
| `totalMillis()` | Wall-clock time of the resolve, after table extraction. |

A `ResolvedChips` that you build with its public constructor returns
`ResolveStats.NONE`, which holds zeros.

#### Additional tags at create

To put tags of your own on the chips the resolver creates, override
`additionalTags(String table, String partitionValue)` on your `ChipFactory`.
The resolver sends them in the create request with the tenant tag and any
freshness tags, so no follow-up `addChipTags` call is needed:

```java
ChipFactory factory = new ChipFactory() {
    // isPartitioned / buildSource as above...

    @Override
    public Map<String, String> additionalTags(String table, String partitionValue) {
        return Map.of("region", region);
    }
};
```

- The resolver never compares additional tags. A chip whose additional tags
  are missing or different stays in use. To rebuild a chip when a value
  changes, use a freshness tag.
- The tags apply at create only. A chip that a search finds keeps the tags it
  has, so chips that predate the override need a one-time `addChipTags` call
  if you want the tags on them.
- When concurrent resolves share one create, the chip carries the tags of the
  resolve that started the create.
- A key that equals the tenant tag key or one of the chip's freshness tag keys
  fails the resolve with an `IllegalArgumentException`.
- The method runs on every resolve, once per table or table and partition
  pair, so return precomputed values.

### Correlation IDs

Tag requests so the engine's logs, ingest jobs and chips can be traced back to
your own request:

```java
DatalatheClient scoped = client.withCorrelationId(MDC.get("CorrelationId"));
scoped.createChipAsync(source, chipId, tags);
resolver.withCorrelationId(MDC.get("CorrelationId")).resolve(queries);
```

`withCorrelationId` returns a lightweight copy that shares the connection pool,
so create one per request. Read the ID on the thread that has it (your MDC is
empty on executor threads) and pass the copy along. Valid IDs match
`[A-Za-z0-9._:-]{1,128}`; a null, blank or invalid ID leaves requests untagged
and never throws. `IngestJob.getCorrelationId()` and chip metadata return the
stored ID.

`ChipResolver.withCorrelationId` returns a view on the same resolver: it
shares the executor, the in-flight creates, the in-flight chip searches and
the empty-source cache, so concurrent requests still stage each chip once. Don't build a second
`ChipResolver` per request for this; separate resolvers don't see each
other's in-flight creates. When two requests race for the same chip, the
chip carries the ID of the request that started the create, and
`searchChips` by the other ID won't list it. A shared chip search carries the
ID of the request that sent it.

A subclass of `DatalatheClient` that overrides request methods must also
override `copyWith(OkHttpClient)` and return itself through the protected
copy constructor, or the copy `withCorrelationId` returns is a plain
`DatalatheClient` without the overrides.

### Connections and proxies

The client pools HTTP connections. It keeps up to 64 idle connections and evicts
an idle one after 30 seconds, so a burst of up to 64 concurrent requests reuses
the connections of a burst that ended less than 30 seconds earlier. After a
longer pause, each request opens a new connection.

That number is deliberately below the idle timeout of anything likely to sit
between you and the engine. A load balancer closes an idle connection on its own
schedule without telling the client, so a pooled connection that outlives the
proxy's idle timeout is dead while still looking reusable. The next request
written to it blocks until the read timeout expires — five minutes of apparent
hang, with nothing arriving at the engine and nothing in its log. OkHttp's own
default keep-alive is five minutes, which collides exactly with a common load
balancer setting.

If you terminate the engine behind a proxy with an idle timeout below 30
seconds, raise the proxy rather than the client.

To keep connections across longer pauses, or to share one pool between clients,
pass your own `ConnectionPool` to the constructor. Set its keep-alive below the
idle timeout of every load balancer or proxy between the client and the engine.
The client uses the pool as is:

```java
// Proxy idle timeout is 300 seconds; evict at 240.
ConnectionPool pool = new ConnectionPool(64, 240, TimeUnit.SECONDS);
DatalatheClient client = new DatalatheClient(
    baseUrl, Collections.emptyMap(), RetryConfig.DEFAULT,
    DatalatheClient.DEFAULT_CATALOG_TIMEOUT, pool);
```

### Catalog call timeout

Chip search, chip lookup, ingest job status, connection and license status, and tag writes have a
5-second read timeout. The engine answers them in milliseconds, so a call with no response in that
time is sent once more. A second timeout throws `SocketTimeoutException`. Stagings, reports, chip
queries, deletes, and AI requests keep the 5-minute read timeout and aren't repeated.

To change the timeout, pass it to the constructor. `null` turns the short timeout and the retry off:

```java
DatalatheClient client = new DatalatheClient(
    baseUrl, Collections.emptyMap(), RetryConfig.DEFAULT, Duration.ofSeconds(10));
```

### Error Handling

Failed API calls throw `IOException`. When the engine returns a structured
error body, the client throws `DatalatheApiException` (an `IOException`
subclass) carrying the HTTP status and the server's machine-readable error
code and message, so you can branch on the cause without matching exception
message text:

```java
import com.datalathe.client.ChipNotFoundException;
import com.datalathe.client.DatalatheApiException;

try {
    client.generateReport(chipIds, queries);
} catch (ChipNotFoundException e) {
    // A referenced chip is no longer available — re-stage it using
    // e.getChipId() and retry
} catch (DatalatheApiException e) {
    // e.getStatusCode(), e.getErrorCode(), e.getServerMessage()
}
```

`ChipNotFoundException` extends `DatalatheApiException`, and both remain
`IOException`s with the same message format as before, so existing catch
blocks and message-based handling are unaffected.

`ChipSource` also carries an optional `failIfEmpty` flag: when `true`, a create
whose source returns no rows fails with the `EMPTY_SOURCE` error code instead
of registering an empty chip. The flag is serialized as `fail_if_empty` only
when set, so requests against older engines are unchanged.

### Data Types

The client supports the following data types:

- `Int32`: 32-bit integers
- `Int64`: 64-bit integers
- `Utf8`: String values
- `Boolean`: True/false values
- `Float32`: Single-precision floating point numbers
- `Float64`: Double-precision floating point numbers

### ResultSet Features

The `DatalatheResultSet` implements the JDBC `ResultSet` interface with the following features:

- Navigation methods (`next()`, `previous()`, `first()`, `last()`, etc.)
- Type conversion methods (`getString()`, `getInt()`, `getBoolean()`, `getDouble()`)
- Column metadata access
- Null value handling
- Column name and index-based access

## Building

To build the project:

```bash
mvn clean install
```

## Testing

Run the test suite:

```bash
mvn test
```

## Security

To scan dependencies for known CVEs (report-only; does not fail the build):

```bash
mvn dependency-check:check
```

Reports are written to `target/dependency-check-report.html` and `target/dependency-check-report.json`.

For higher NVD API rate limits, add your [NVD API key](https://nvd.nist.gov/developers/request-an-api-key) to `~/.m2/settings.xml` under a server with id `nvd` (use the key as the `password`; `username` can be anything or omitted):

```xml
<servers>
  <server>
    <id>nvd</id>
    <password>YOUR_NVD_API_KEY</password>
  </server>
</servers>
```

## Requirements

- Java 18 or higher
- Maven 3.6 or higher

## Dependencies

- OkHttp
- Jackson Databind
- JUnit Jupiter (for testing)

See `pom.xml` for the current versions.

## License

This project is licensed under the MIT License - see the LICENSE file for details.
