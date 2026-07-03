package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.types.GenerateReportResponse;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class TolerantDeserializationTest {

    @Test
    void createChipToleratesUnknownResponseFields() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody(
                    "{\"chip_id\":\"chip-123\",\"some_future_field\":\"x\",\"another_new_one\":true}"));
            server.start();
            DatalatheClient client = new DatalatheClient(
                    server.url("/").toString().replaceAll("/$", ""));

            String chipId = client.createChip("src", "SELECT 1", "t");

            assertEquals("chip-123", chipId);
        }
    }

    @Test
    void generateReportToleratesUnknownResponseFields() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody(
                    "{\"result\":{\"0\":{\"data\":[[\"1\"]],\"future_nested\":\"y\"}},"
                    + "\"timing\":{\"total_ms\":5,\"chip_attach_ms\":1,"
                    + "\"query_execution_ms\":4,\"future_timing_field\":9},"
                    + "\"some_future_top_field\":\"z\"}"));
            server.start();
            DatalatheClient client = new DatalatheClient(
                    server.url("/").toString().replaceAll("/$", ""));

            GenerateReportResult report = client.generateReport(
                    List.of("chip-123"), List.of("SELECT 1"), null, null, true);

            assertNotNull(report);
            GenerateReportResponse.Result result = report.getResults().get(0);
            assertNotNull(result);
            assertEquals(List.of(List.of("1")), result.getData());
            assertEquals(5L, report.getTiming().getTotalMs());
            assertTrue(report.getResults().containsKey(0));
        }
    }
}
