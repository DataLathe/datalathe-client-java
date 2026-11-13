package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datalathe.client.types.SeriesRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SeriesRefTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aNamedSlotIsSentAsPartition() {
        JsonNode json = mapper.valueToTree(SeriesRef.of("1234", "2026-10-31"));
        assertEquals("1234", json.get("id").asText());
        assertEquals("2026-10-31", json.get("partition").asText());
    }

    @Test
    void aSeriesWithoutASlotNameOmitsPartition() {
        JsonNode json = mapper.valueToTree(SeriesRef.of("1234"));
        assertFalse(json.has("partition"));
        assertFalse(json.has("hold"));
    }

    @Test
    void aHeldSeriesSendsHold() {
        JsonNode json = mapper.valueToTree(SeriesRef.held("1234"));
        assertEquals(true, json.get("hold").asBoolean());
    }
}
