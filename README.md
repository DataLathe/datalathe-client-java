# Datalathe Java Client

A Java client library for interacting with Datalathe, providing a JDBC-compatible interface for querying and managing data.

## Features

- JDBC-compatible `ResultSet` implementation
- Support for multiple data types (Int32, Utf8, Boolean, Float64)
- Batch query execution
- Data staging capabilities
- Null value handling
- Type conversion and metadata support

## Installation

The library is published to [Maven Central](https://central.sonatype.com/artifact/com.datalathe/datalathe-client). Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.datalathe</groupId>
    <artifactId>datalathe-client</artifactId>
    <version>1.8.1</version>
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

### Data Types

The client supports the following data types:

- `Int32`: 32-bit integers
- `Utf8`: String values
- `Boolean`: True/false values
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
