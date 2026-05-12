package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class UnreadableChipIdsTest {

    @Test
    void listChipsSurfacesUnreadableChipIds() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String body = "{"
                    + "\"chips\":[{\"chip_id\":\"good-1\",\"sub_chip_id\":\"sub-1\","
                    + "\"table_name\":\"users\",\"partition_value\":\"default\"}],"
                    + "\"metadata\":[],"
                    + "\"unreadable_chip_ids\":[\"bad-1\",\"bad-2\"]}";
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body));
            server.start();

            DatalatheClient client = new DatalatheClient(
                    server.url("/").toString().replaceAll("/$", ""));
            SearchChipsResponse response = client.listChips();

            assertEquals(1, response.getChips().size());
            assertEquals("good-1", response.getChips().get(0).getChipId());
            assertEquals(List.of("bad-1", "bad-2"), response.getUnreadableChipIds());
        }
    }

    @Test
    void listChipsDefaultsUnreadableChipIdsToEmptyList() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String body = "{\"chips\":[],\"metadata\":[]}";
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body));
            server.start();

            DatalatheClient client = new DatalatheClient(
                    server.url("/").toString().replaceAll("/$", ""));
            SearchChipsResponse response = client.listChips();

            assertNotNull(response.getUnreadableChipIds());
            assertTrue(response.getUnreadableChipIds().isEmpty());
        }
    }
}
