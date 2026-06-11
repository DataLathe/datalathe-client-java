package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

class ListChipsPaginationTest {

    private static final String PAGE_BODY = "{"
            + "\"chips\":[{\"chip_id\":\"chip-1\",\"sub_chip_id\":\"sub-1\","
            + "\"table_name\":\"users\",\"partition_value\":\"default\"}],"
            + "\"metadata\":[],"
            + "\"unreadable_chip_ids\":[\"bad-1\"],"
            + "\"total_count\":42}";

    @Test
    void listChipsSendsLimitAndOffset() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(PAGE_BODY));
            server.start();

            DatalatheClient client = new DatalatheClient(
                    server.url("/").toString().replaceAll("/$", ""));
            SearchChipsResponse response = client.listChips(10, 20);

            RecordedRequest request = server.takeRequest();
            assertEquals("/lathe/chips?limit=10&offset=20", request.getPath());
            assertEquals(1, response.getChips().size());
            assertEquals(Long.valueOf(42), response.getTotalCount());
            assertEquals(1, response.getUnreadableChipIds().size());
        }
    }

    @Test
    void listChipsSendsOnlyProvidedParams() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(PAGE_BODY));
            server.start();

            DatalatheClient client = new DatalatheClient(
                    server.url("/").toString().replaceAll("/$", ""));
            client.listChips(5, null);

            assertEquals("/lathe/chips?limit=5", server.takeRequest().getPath());
        }
    }

    @Test
    void listChipsWithoutPaginationKeepsBarePath() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"chips\":[],\"metadata\":[]}"));
            server.start();

            DatalatheClient client = new DatalatheClient(
                    server.url("/").toString().replaceAll("/$", ""));
            SearchChipsResponse response = client.listChips();

            assertEquals("/lathe/chips", server.takeRequest().getPath());
            assertNull(response.getTotalCount());
        }
    }
}
