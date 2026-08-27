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
    <version>1.11.0</version>
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
the total count.

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
