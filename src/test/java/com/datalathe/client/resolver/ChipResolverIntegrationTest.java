package com.datalathe.client.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.SearchChipsResponse;
import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.SourceType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The resolver against a live engine: a create carries the tenant tag and the
 * factory's additional tags, the next resolve finds the chip, and concurrent
 * resolves agree on it. Run via {@code dagger call integration-java} or
 * {@code mvn test -Pintegration} with DATALATHE_URL set.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DATALATHE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChipResolverIntegrationTest {

    private static final String TABLE_NAME = "resolver_test";
    private static final String TAG_KEY = "int_java_resolver";

    private final String tagValue = "run-" + System.currentTimeMillis();
    private final List<String> createdChipIds = new ArrayList<>();
    private DatalatheClient client;
    private ExecutorService callers;
    private ChipFactory factory;

    @BeforeAll
    void setUp() {
        client = new DatalatheClient(System.getenv("DATALATHE_URL"));
        callers = Executors.newFixedThreadPool(6);
        String csvPath = System.getenv().getOrDefault("E2E_CSV_PATH", "/tmp/test-data.csv");
        factory = new ChipFactory() {
            @Override
            public boolean isPartitioned(String table) {
                return false;
            }

            @Override
            public ChipSource buildSource(String table, String partitionValue) {
                return ChipSource.builder()
                        .databaseName("")
                        .query("")
                        .filePath(csvPath)
                        .tableName(table)
                        .sourceType(SourceType.FILE)
                        .build();
            }

            @Override
            public Map<String, String> additionalTags(String table, String partitionValue) {
                return Map.of("int_java_owner", "reports");
            }
        };
    }

    @AfterAll
    void cleanUp() {
        callers.shutdownNow();
        for (String chipId : createdChipIds) {
            try {
                client.deleteChip(chipId);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private ResolvedChips resolve(ChipResolver resolver) throws IOException {
        return resolver.resolveForTables(Set.of(TABLE_NAME), List.of(), TAG_KEY, tagValue, factory);
    }

    @Test
    void createCarriesAdditionalTagsAndLaterResolvesFindTheChip() throws Exception {
        ChipResolver resolver = new ChipResolver(client);

        ResolvedChips created = resolve(resolver);
        createdChipIds.addAll(created.allChipIds());
        assertEquals(1, created.size());
        assertEquals(1, created.stats().chipsCreated());
        assertEquals(0, created.stats().chipsFound());
        String chipId = created.allChipIds().get(0);

        SearchChipsResponse search = client.searchChips(null, null, TAG_KEY, tagValue);
        Map<String, String> tags = new HashMap<>();
        for (SearchChipsResponse.ChipTagRecord tag : search.getTags()) {
            if (chipId.equals(tag.getChipId())) {
                tags.put(tag.getKey(), tag.getValue());
            }
        }
        assertEquals(tagValue, tags.get(TAG_KEY));
        assertEquals("reports", tags.get("int_java_owner"));

        ResolvedChips foundAgain = resolve(resolver);
        assertEquals(List.of(chipId), foundAgain.allChipIds());
        assertEquals(1, foundAgain.stats().chipsFound());
        assertEquals(0, foundAgain.stats().chipsCreated());

        List<Future<ResolvedChips>> concurrent = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ChipResolver view = resolver.withCorrelationId("int-java-resolver-" + i);
            concurrent.add(callers.submit(() -> resolve(view)));
        }
        for (Future<ResolvedChips> result : concurrent) {
            ResolvedChips chips = result.get(60, TimeUnit.SECONDS);
            assertEquals(List.of(chipId), chips.allChipIds());
            assertEquals(0, chips.stats().chipsCreated());
            assertTrue(chips.stats().searchMillis() >= 0);
        }
    }
}
