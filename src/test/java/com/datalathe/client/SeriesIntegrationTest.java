package com.datalathe.client;

import com.datalathe.client.resolver.ChipFactory;
import com.datalathe.client.resolver.ChipResolver;
import com.datalathe.client.resolver.ResolvedChips;
import com.datalathe.client.resolver.SeriesNamer;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.GenerateReportResponse;
import com.datalathe.client.types.IngestJobHandle;
import com.datalathe.client.types.SeriesPublishResult;
import com.datalathe.client.types.SeriesRef;
import com.datalathe.client.types.SourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Series publishing against a live engine with a chip manager. The file at
 * E2E_CSV_PATH is an engine-side path holding five data rows.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DATALATHE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeriesIntegrationTest {

    private static final String TABLE_NAME = "series_test";

    private final String run = "int-java-series-" + System.currentTimeMillis();
    private final List<String> created = new ArrayList<>();
    private DatalatheClient client;
    private String csvPath;

    @BeforeAll
    void setUp() {
        client = new DatalatheClient(System.getenv("DATALATHE_URL"));
        csvPath = System.getenv().getOrDefault("E2E_CSV_PATH", "/tmp/test-data.csv");
    }

    @AfterAll
    void cleanUp() {
        for (String chipId : created) {
            try {
                client.deleteChip(chipId);
            } catch (IOException ignored) {
                // a test may have deleted it already
            }
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

    private String rowsThrough(String reference) throws IOException {
        Map<Integer, GenerateReportResponse.Result> results = client.generateReport(
                List.of(reference),
                List.of("SELECT COUNT(*) FROM " + TABLE_NAME));
        return results.get(0).getResult().get(0).get(0);
    }

    private String create(SeriesRef series) throws IOException {
        String chipId = client.createChip(fileSource(), null, null, series);
        created.add(chipId);
        return chipId;
    }

    @Test
    void theResolverPublishesIntoTheTenantSeries() throws Exception {
        String series = run + "-resolver";
        ChipFactory factory = new ChipFactory() {
            @Override
            public boolean isPartitioned(String table) {
                return false;
            }

            @Override
            public ChipSource buildSource(String table, String partitionValue) {
                return fileSource();
            }
        };
        ChipResolver resolver = new ChipResolver(client).withSeries(SeriesNamer.tagValue("tenant"));

        ResolvedChips chips = resolver.resolveForTables(Set.of(TABLE_NAME), List.of(), "tenant", series, factory);
        created.addAll(chips.allChipIds());

        assertEquals(1, chips.allChipIds().size());
        assertEquals("5", rowsThrough("series:" + series + "@latest"));
    }

    @Test
    void namedSlotsKeepUnpartitionedChipsApart() throws IOException {
        String series = run + "-slots";
        create(SeriesRef.of(series, "2026-09-30"));
        create(SeriesRef.of(series, "2026-10-31"));
        assertEquals("10", rowsThrough("series:" + series + "@latest"));
        assertEquals("5", rowsThrough("series:" + series + "/" + TABLE_NAME + "/2026-10-31@latest"));

        create(SeriesRef.of(series, "2026-10-31"));
        assertEquals("10", rowsThrough("series:" + series + "@latest"));
    }

    @Test
    void aHeldChipJoinsItsSeriesOnPublish() throws Exception {
        String series = run + "-held";
        String reference = "series:" + series + "@latest";

        IngestJobHandle job = client.createChipAsync(fileSource(), null, Map.of("tenant", series),
                SeriesRef.held(series));
        created.add(job.getChipId());
        client.waitForIngest(job.getJobId(), Duration.ofSeconds(1), Duration.ofMinutes(2));

        assertEquals("5", rowsThrough(job.getChipId()));
        assertThrows(IOException.class, () -> rowsThrough(reference));

        SeriesPublishResult published = client.publishToSeries(job.getChipId());
        assertEquals("published", published.getOutcome());
        assertEquals(series, published.getSeriesId());
        assertEquals("5", rowsThrough(reference));

        assertEquals("already_published", client.publishToSeries(job.getChipId()).getOutcome());
    }

    @Test
    void aDeletedHeldChipLeavesTheSeriesAsItWas() throws IOException {
        String series = run + "-discard";
        String reference = "series:" + series + "@latest";
        create(SeriesRef.of(series));

        String held = create(SeriesRef.held(series));
        client.deleteChip(held);

        assertEquals("5", rowsThrough(reference));
        assertThrows(IOException.class, () -> client.publishToSeries(held));
    }

    @Test
    void publishRefusesAChipWithoutAHeldVersion() throws IOException {
        String plain = client.createChip(fileSource(), null);
        created.add(plain);
        assertThrows(IOException.class, () -> client.publishToSeries(plain));
    }
}
