package com.datalathe.client;

import com.datalathe.client.types.ChipQueryResult;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.GenerateReportResponse;
import com.datalathe.client.types.SourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full chip lifecycle against a live engine: stage from a FILE source,
 * report and raw-SQL queries, duplicate-stage rejection, delete. Mirrors
 * the JS and Python SDK integration suites; the file at E2E_CSV_PATH is
 * an engine-side path holding five data rows. Run via
 * {@code dagger call integration-java} or {@code mvn test -Pintegration}
 * with DATALATHE_URL set.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DATALATHE_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChipLifecycleIntegrationTest {

    private static final String TABLE_NAME = "stage_test";

    private final String chipId = "int-java-" + System.currentTimeMillis();
    private DatalatheClient client;
    private String csvPath;

    @BeforeAll
    void setUp() {
        client = new DatalatheClient(System.getenv("DATALATHE_URL"));
        csvPath = System.getenv().getOrDefault("E2E_CSV_PATH", "/tmp/test-data.csv");
    }

    @AfterAll
    void cleanUp() {
        try {
            client.deleteChip(chipId);
        } catch (IOException ignored) {
            // primary delete may have already run
        }
    }

    private ChipSource fileSource() {
        return ChipSource.builder()
                .databaseName("")
                .query("")
                .filePath(csvPath)
                .tableName(TABLE_NAME)
                .sourceType(SourceType.FILE)
                .build();
    }

    private boolean chipInSearch() throws IOException {
        List<SearchChipsResponse.ChipRecord> chips = client.searchChips(TABLE_NAME, null).getChips();
        return chips != null && chips.stream().anyMatch(c -> chipId.equals(c.getChipId()));
    }

    @Test
    @Order(1)
    void stagesChipFromFileSource() throws IOException {
        client.createChip(fileSource(), chipId);
        assertTrue(chipInSearch());
    }

    @Test
    @Order(2)
    void returnsRowsViaGenerateReport() throws IOException {
        Map<Integer, GenerateReportResponse.Result> results = client.generateReport(
                List.of(chipId),
                List.of("SELECT COUNT(*) FROM " + TABLE_NAME));
        GenerateReportResponse.Result entry = results.get(0);
        assertNotNull(entry);
        assertEquals("5", entry.getResult().get(0).get(0));
    }

    @Test
    @Order(3)
    void runsRawSqlAgainstChipCatalog() throws IOException {
        String catalog = chipId.replace('-', '_');
        ChipQueryResult result = client.queryChips(
                List.of(chipId),
                "SELECT COUNT(*) AS n FROM " + catalog + ".main." + TABLE_NAME);
        assertEquals("n", result.getColumns().get(0).getName());
        assertEquals("5", result.getRows().get(0).get(0));
        assertFalse(result.isTruncated());
    }

    @Test
    @Order(4)
    void rejectsRestageWithTableAlreadyExists() {
        IOException e = assertThrows(IOException.class, () -> client.createChip(fileSource(), chipId));
        String text = e.getMessage();
        if (e instanceof DatalatheApiException) {
            text = text + " " + ((DatalatheApiException) e).getErrorCode()
                    + " " + ((DatalatheApiException) e).getServerMessage();
        }
        assertTrue(text.matches("(?is).*(TABLE_ALREADY_EXISTS|already exists).*"),
                "unexpected duplicate-stage error: " + text);
    }

    @Test
    @Order(5)
    void deletesChip() throws IOException {
        client.deleteChip(chipId);
        assertFalse(chipInSearch());
    }
}
