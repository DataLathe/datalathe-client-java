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
    <version>1.0.0</version>
</dependency>
```

## Usage

### Basic Query Example

```java
DatalatheClient client = new DatalatheClient("http://localhost:8080");

// Stage data from a single query
String stageQuery = "SELECT * FROM users";
List<String> chipIds = client.stageData("my_database", stageQuery, "my_table");

// Execute multiple queries on the staged data
List<String> queries = Arrays.asList(
    "SELECT * FROM my_table",
    "SELECT COUNT(*) FROM my_table"
);
Map<Integer, ResultSet> results = client.query(chipIds, queries);

// Process results
ResultSet rs = results.get(0);
while (rs.next()) {
    String name = rs.getString("name");
    int age = rs.getInt("age");
    // Process data...
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

- Java 11 or higher
- Maven 3.6 or higher

## Dependencies

- OkHttp 4.12.0
- Jackson Databind 2.16.0
- JUnit Jupiter 5.10.2 (for testing)

## License

This project is licensed under the MIT License - see the LICENSE file for details.
