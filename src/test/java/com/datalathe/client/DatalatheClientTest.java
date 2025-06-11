package com.datalathe.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DatalatheClientTest {
        private MockWebServer server;
        private DatalatheClient client;
        @SuppressWarnings("unused")
        private ObjectMapper objectMapper;

        @Before
        public void setUp() throws IOException {
                server = new MockWebServer();
                server.start();
                client = new DatalatheClient(server.url("/").toString());
                objectMapper = new ObjectMapper();
        }

        @After
        public void tearDown() throws IOException {
                server.shutdown();
        }

        @Test
        public void testStageData() throws IOException, InterruptedException {
                // Prepare test data
                String sourceName = "test_db";
                String query = "SELECT * FROM users";
                String tableName = "test_table";

                // Mock response
                server.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .setBody("{\"chip_id\": \"chip1\", \"error\": null}"));

                // Execute test
                String chipId = client.stageData(sourceName, query, tableName);

                // Verify results
                assertEquals("chip1", chipId);

                // Verify request
                assertEquals(1, server.getRequestCount());
                String request = server.takeRequest().getBody().readUtf8();
                assertTrue(request.contains("\"source_type\":\"MYSQL\""));
                assertTrue(request.contains("\"query\":\"SELECT * FROM users\""));
                assertTrue(request.contains("\"database_name\":\"test_db\""));
                assertTrue(request.contains("\"table_name\":\"test_table\""));
        }

        @Test
        public void testQuery() throws Exception {
                // Prepare test data
                List<String> chipIds = Arrays.asList("chip1", "chip2");
                List<String> queries = Arrays.asList(
                                "SELECT * FROM users",
                                "SELECT * FROM orders");

                // Mock response
                String responseJson = "{\"result\":{" +
                                "\"0\":{" +
                                "\"result\":[[\"user1\",\"172\"],[\"user2\",\"173\"]]," +
                                "\"schema\":[{\"name\":\"id\",\"data_type\":\"Utf8\"},{\"name\":\"companyId\",\"data_type\":\"Int32\"}],"
                                +
                                "\"error\":null" +
                                "}," +
                                "\"1\":{" +
                                "\"result\":[[\"order1\",\"100\"],[\"order2\",\"200\"]]," +
                                "\"schema\":[{\"name\":\"id\",\"data_type\":\"Utf8\"},{\"name\":\"amount\",\"data_type\":\"Int32\"}],"
                                +
                                "\"error\":null" +
                                "}" +
                                "}}";

                server.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .setBody(responseJson));

                // Execute test
                Map<Integer, ResultSet> results = client.query(chipIds, queries);

                // Verify results
                assertEquals(2, results.size());

                // Verify first result set
                ResultSet rs1 = results.get(0);
                assertNotNull(rs1);
                assertTrue(rs1.next());
                assertEquals("user1", rs1.getString(1));
                assertEquals(172, rs1.getInt(2));
                assertTrue(rs1.next());
                assertEquals("user2", rs1.getString(1));
                assertEquals(173, rs1.getInt(2));
                assertFalse(rs1.next());

                // Verify second result set
                ResultSet rs2 = results.get(1);
                assertNotNull(rs2);
                assertTrue(rs2.next());
                assertEquals("order1", rs2.getString(1));
                assertEquals(100, rs2.getInt(2));
                assertTrue(rs2.next());
                assertEquals("order2", rs2.getString(1));
                assertEquals(200, rs2.getInt(2));
                assertFalse(rs2.next());

                // Verify request
                assertEquals(1, server.getRequestCount());
                String request = server.takeRequest().getBody().readUtf8();
                assertTrue(request.contains("\"chip_id\":[\"chip1\",\"chip2\"]"));
                assertTrue(request.contains("\"source_type\":\"LOCAL\""));
                assertTrue(request.contains("\"query\":[\"SELECT * FROM users\",\"SELECT * FROM orders\"]"));
        }

        @Test
        public void testQueryWithError() throws Exception {
                // Prepare test data
                List<String> chipIds = Arrays.asList("chip1");
                List<String> queries = Arrays.asList("SELECT * FROM users");

                // Mock response with error
                String responseJson = "{\"result\":{" +
                                "\"0\":{" +
                                "\"error\":\"Table not found\"," +
                                "\"result\":null," +
                                "\"schema\":null" +
                                "}" +
                                "}}";

                server.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .setBody(responseJson));

                // Execute test
                Map<Integer, ResultSet> results = client.query(chipIds, queries);

                // Verify results - should be empty since the result had an error
                assertTrue(results.isEmpty());
        }
}