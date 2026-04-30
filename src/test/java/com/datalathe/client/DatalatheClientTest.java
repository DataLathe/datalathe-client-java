package com.datalathe.client;

import com.datalathe.client.types.AgentOptions;
import com.datalathe.client.types.AgentRequest;
import com.datalathe.client.types.AgentResponse;
import com.datalathe.client.types.AiCredential;
import com.datalathe.client.types.AiQueryRequest;
import com.datalathe.client.types.AiQueryResponse;
import com.datalathe.client.types.ConversationTurn;
import com.datalathe.client.types.CreateAiCredentialRequest;
import com.datalathe.client.types.GenerateReportResponse;
import com.datalathe.client.types.StopReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
                String chipId = client.createChip(sourceName, query, tableName);

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
                Map<Integer, GenerateReportResponse.Result> results = client.generateReport(chipIds,
                                queries);

                // Verify results
                assertEquals(2, results.size());

                // Verify first result set
                ResultSet rs1 = results.get(0).getResultSet();
                assertNotNull(rs1);
                assertTrue(rs1.next());
                assertEquals("user1", rs1.getString(1));
                assertEquals(172, rs1.getInt(2));
                assertTrue(rs1.next());
                assertEquals("user2", rs1.getString(1));
                assertEquals(173, rs1.getInt(2));
                assertFalse(rs1.next());

                // Verify second result set
                ResultSet rs2 = results.get(1).getResultSet();
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
                assertTrue(request.contains("\"source_type\":\"CHIP\""));
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
                Map<Integer, GenerateReportResponse.Result> results = client.generateReport(chipIds,
                                queries);

                // Verify results - error entry is still included in results map
                assertEquals(1, results.size());
                assertEquals("Table not found", results.get(0).getError());
                assertNull(results.get(0).getResult());
        }

        @Test
        public void testQueryWithTiming() throws Exception {
                List<String> chipIds = Arrays.asList("chip1");
                List<String> queries = Arrays.asList("SELECT * FROM users");

                String responseJson = "{\"result\":{" +
                                "\"0\":{" +
                                "\"result\":[[\"user1\"]],"+
                                "\"schema\":[{\"name\":\"id\",\"data_type\":\"Utf8\"}]," +
                                "\"error\":null" +
                                "}" +
                                "}," +
                                "\"timing\":{\"total_ms\":150,\"chip_attach_ms\":30,\"query_execution_ms\":120}}";

                server.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .setBody(responseJson));

                GenerateReportResult reportResult = client.generateReport(chipIds, queries, null, null);

                assertNotNull(reportResult.getTiming());
                assertEquals(150, reportResult.getTiming().getTotalMs());
                assertEquals(30, reportResult.getTiming().getChipAttachMs());
                assertEquals(120, reportResult.getTiming().getQueryExecutionMs());
                assertEquals(1, reportResult.getResults().size());
        }

        @Test
        public void testQueryWithTransformQuery() throws Exception {
                List<String> chipIds = Arrays.asList("chip1");
                List<String> queries = Arrays.asList("SELECT IFNULL(name, 'N/A') FROM users");

                String responseJson = "{\"result\":{" +
                                "\"0\":{" +
                                "\"result\":[[\"user1\"]],"+
                                "\"schema\":[{\"name\":\"name\",\"data_type\":\"Utf8\"}]," +
                                "\"error\":null," +
                                "\"transformed_query\":\"SELECT COALESCE(name, 'N/A') FROM users\"" +
                                "}" +
                                "}," +
                                "\"timing\":{\"total_ms\":100,\"chip_attach_ms\":20,\"query_execution_ms\":80}}";

                server.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .setBody(responseJson));

                GenerateReportResult reportResult = client.generateReport(chipIds, queries, true, true);

                // Verify transform query was sent in request
                String request = server.takeRequest().getBody().readUtf8();
                assertTrue(request.contains("\"transform_query\":true"));
                assertTrue(request.contains("\"return_transformed_query\":true"));

                // Verify transformed query in response
                assertEquals("SELECT COALESCE(name, 'N/A') FROM users",
                                reportResult.getResults().get(0).getTransformedQuery());

                // Verify timing is present
                assertNotNull(reportResult.getTiming());
                assertEquals(100, reportResult.getTiming().getTotalMs());
        }

        @Test
        public void testAiQueryWithSessionId() throws Exception {
                String responseJson = "{" +
                        "\"request_id\":\"req1\"," +
                        "\"explanation\":\"Total revenue is $1M\"," +
                        "\"generated_sql\":\"SELECT SUM(revenue) FROM sales\"," +
                        "\"assistant_turn\":{\"role\":\"assistant\",\"content\":\"Total revenue is $1M\"}," +
                        "\"session_id\":\"sess-abc\"" +
                        "}";

                server.enqueue(new MockResponse()
                        .setResponseCode(200)
                        .setBody(responseJson));

                AiQueryResponse result = client.aiQuery(AiQueryRequest.builder()
                        .contextId("ctx1")
                        .userQuestion("What is total revenue?")
                        .sessionId("sess-abc")
                        .build());

                assertEquals("sess-abc", result.getSessionId());
                assertNotNull(result.getAssistantTurn());
                assertEquals("assistant", result.getAssistantTurn().getRole());
                assertEquals("Total revenue is $1M", result.getAssistantTurn().getContent());

                RecordedRequest request = server.takeRequest();
                String body = request.getBody().readUtf8();
                assertTrue(body.contains("\"session_id\":\"sess-abc\""));
                assertTrue(body.contains("\"context_id\":\"ctx1\""));
                assertFalse(body.contains("\"credential_id\""));
        }

        @Test
        public void testAiConversation() throws Exception {
                String response1Json = "{" +
                        "\"request_id\":\"req1\"," +
                        "\"explanation\":\"Revenue is $1M\"," +
                        "\"assistant_turn\":{\"role\":\"assistant\",\"content\":\"Revenue is $1M\"}" +
                        "}";
                String response2Json = "{" +
                        "\"request_id\":\"req2\"," +
                        "\"explanation\":\"By region: US $600K, EU $400K\"," +
                        "\"assistant_turn\":{\"role\":\"assistant\",\"content\":\"By region: US $600K, EU $400K\"}" +
                        "}";

                server.enqueue(new MockResponse().setResponseCode(200).setBody(response1Json));
                server.enqueue(new MockResponse().setResponseCode(200).setBody(response2Json));

                AiConversation conversation = client.aiConversation("ctx1", "cred1");

                AiQueryResponse r1 = conversation.ask("What is total revenue?");
                assertEquals("Revenue is $1M", r1.getExplanation());

                // First call should have no conversation history
                RecordedRequest req1 = server.takeRequest();
                String body1 = req1.getBody().readUtf8();
                assertFalse(body1.contains("conversation_history"));

                AiQueryResponse r2 = conversation.ask("Break that down by region");
                assertEquals("By region: US $600K, EU $400K", r2.getExplanation());

                // Second call should include history
                RecordedRequest req2 = server.takeRequest();
                String body2 = req2.getBody().readUtf8();
                assertTrue(body2.contains("conversation_history"));
                assertTrue(body2.contains("What is total revenue?"));
                assertTrue(body2.contains("Revenue is $1M"));

                // History should have 4 turns
                List<ConversationTurn> history = conversation.getHistory();
                assertEquals(4, history.size());
                assertEquals("user", history.get(0).getRole());
                assertEquals("What is total revenue?", history.get(0).getContent());
                assertEquals("assistant", history.get(3).getRole());
        }

        @Test
        public void testDeleteAiSession() throws Exception {
                server.enqueue(new MockResponse().setResponseCode(200));

                client.deleteAiSession("sess-abc");

                RecordedRequest request = server.takeRequest();
                assertEquals("DELETE", request.getMethod());
                assertTrue(request.getPath().contains("/lathe/ai/sessions/sess-abc"));
        }

        @Test
        public void testChipNotFoundExceptionOnStructured404() throws Exception {
                // Wire format contract with the engine: HTTP 404 + body
                // {error_code: "chip_not_found", chip_id: ...} → ChipNotFoundException.
                server.enqueue(new MockResponse()
                                .setResponseCode(404)
                                .setHeader("Content-Type", "application/json")
                                .setBody("{\"error\":\"Chip 'abc123' is not available (may have expired)\","
                                                + "\"error_code\":\"chip_not_found\",\"chip_id\":\"abc123\"}"));

                try {
                        client.generateReport(Arrays.asList("abc123"), Arrays.asList("SELECT 1"));
                        fail("Expected ChipNotFoundException");
                } catch (ChipNotFoundException e) {
                        assertEquals("abc123", e.getChipId());
                        // Back-compat: still catchable as IOException.
                        assertTrue(e instanceof IOException);
                }
        }

        @Test
        public void testFallsBackToIOExceptionWhenErrorCodeMissing() throws Exception {
                server.enqueue(new MockResponse()
                                .setResponseCode(404)
                                .setHeader("Content-Type", "application/json")
                                .setBody("{\"error\":\"Some other 404\"}"));

                try {
                        client.generateReport(Arrays.asList("x"), Arrays.asList("SELECT 1"));
                        fail("Expected IOException");
                } catch (ChipNotFoundException e) {
                        fail("Should not have thrown ChipNotFoundException");
                } catch (IOException expected) {
                        // OK
                }
        }

        @Test
        public void testCreateAiCredentialWithBedrockRegion() throws Exception {
                server.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .setBody("{\"credential_id\":\"cred1\",\"name\":\"bedrock-prod\","
                                                + "\"provider\":\"bedrock\","
                                                + "\"default_model\":\"anthropic.claude-sonnet-4-5-20250929-v1:0\","
                                                + "\"created_at\":1,\"region\":\"us-west-2\"}"));

                AiCredential cred = client.createAiCredential(CreateAiCredentialRequest.builder()
                                .name("bedrock-prod")
                                .provider("bedrock")
                                .apiKey("secret")
                                .defaultModel("anthropic.claude-sonnet-4-5-20250929-v1:0")
                                .region("us-west-2")
                                .build());

                assertEquals("us-west-2", cred.getRegion());

                String body = server.takeRequest().getBody().readUtf8();
                assertTrue(body.contains("\"region\":\"us-west-2\""));
                assertTrue(body.contains("\"default_model\":\"anthropic.claude-sonnet-4-5-20250929-v1:0\""));
                assertTrue(body.contains("\"api_key\":\"secret\""));
        }

        @Test
        public void testCreateAiCredentialOmitsRegionForNonBedrock() throws Exception {
                server.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .setBody("{\"credential_id\":\"cred1\",\"name\":\"anthropic\","
                                                + "\"provider\":\"anthropic\","
                                                + "\"default_model\":\"claude-sonnet-4-5-20250929\","
                                                + "\"created_at\":1}"));

                client.createAiCredential("anthropic", "anthropic", "secret",
                                "claude-sonnet-4-5-20250929");

                String body = server.takeRequest().getBody().readUtf8();
                // region must be omitted entirely (NON_NULL on the request type)
                assertFalse(body.contains("\"region\""));
        }

        @Test
        public void testAiAgentParsesFullResponse() throws Exception {
                String responseJson = "{"
                                + "\"request_id\":\"req1\","
                                + "\"answer\":\"LATAM had the highest growth at 23%.\","
                                + "\"attachments\":[{"
                                + "  \"caption\":\"Growth by region\","
                                + "  \"sql\":\"SELECT region, growth FROM sales\","
                                + "  \"data\":{\"columns\":[{\"name\":\"region\",\"data_type\":\"Utf8\"},"
                                + "                        {\"name\":\"growth\",\"data_type\":\"Float64\"}],"
                                + "           \"rows\":[[\"LATAM\",\"0.23\"]]},"
                                + "  \"visualization\":{\"type\":\"bar\",\"x_axis\":\"region\",\"y_axis\":\"growth\"}"
                                + "}],"
                                + "\"tool_calls\":[{"
                                + "  \"iteration\":1,\"tool\":\"list_tables\",\"args\":{},"
                                + "  \"result_summary\":\"Found 3 tables\",\"duration_ms\":12,\"is_error\":false"
                                + "}],"
                                + "\"narration\":[{\"kind\":\"assistant_text\",\"iteration\":1,"
                                + "                \"text\":\"Let me look at the tables.\"}],"
                                + "\"session_id\":\"sess-abc\","
                                + "\"stop_reason\":\"end_turn\","
                                + "\"usage\":{\"input_tokens\":1500,\"output_tokens\":200,"
                                + "          \"model\":\"claude-sonnet-4-5-20250929\","
                                + "          \"iterations\":2,\"tool_calls\":1}"
                                + "}";

                server.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

                AgentResponse result = client.aiAgent(AgentRequest.builder()
                                .contextId("ctx1")
                                .credentialId("cred1")
                                .userQuestion("Which region had the highest growth?")
                                .sessionId("sess-abc")
                                .agentOptions(AgentOptions.builder()
                                                .maxIterations(5)
                                                .runSqlRowCap(1000)
                                                .build())
                                .build());

                assertEquals("req1", result.getRequestId());
                assertEquals("LATAM had the highest growth at 23%.", result.getAnswer());
                assertEquals(StopReason.END_TURN, result.getStopReason());
                assertEquals(1, result.getAttachments().size());
                assertEquals("Growth by region", result.getAttachments().get(0).getCaption());
                assertEquals("region",
                                result.getAttachments().get(0).getVisualization().getXAxis());
                assertEquals(1, result.getToolCalls().size());
                assertEquals(1, result.getToolCalls().get(0).getIteration());
                assertEquals("Found 3 tables", result.getToolCalls().get(0).getResultSummary());
                assertFalse(result.getToolCalls().get(0).isError());
                assertEquals("assistant_text", result.getNarration().get(0).getKind());
                assertEquals(1, result.getUsage().getToolCalls());
                assertEquals(1500, result.getUsage().getInputTokens());

                RecordedRequest request = server.takeRequest();
                assertTrue(request.getPath().endsWith("/lathe/ai/agent"));
                String body = request.getBody().readUtf8();
                assertTrue(body.contains("\"context_id\":\"ctx1\""));
                assertTrue(body.contains("\"user_question\":\"Which region had the highest growth?\""));
                assertTrue(body.contains("\"credential_id\":\"cred1\""));
                assertTrue(body.contains("\"session_id\":\"sess-abc\""));
                assertTrue(body.contains("\"max_iterations\":5"));
                assertTrue(body.contains("\"run_sql_row_cap\":1000"));
                // Unset cap fields must be omitted (NON_NULL on AgentOptions)
                assertFalse(body.contains("max_tool_calls"));
        }

        @Test
        public void testAiAgentOmitsAgentOptionsWhenNotProvided() throws Exception {
                server.enqueue(new MockResponse().setResponseCode(200).setBody(
                                "{\"request_id\":\"r\",\"answer\":\"ok\","
                                                + "\"attachments\":[],\"tool_calls\":[],\"narration\":[]}"));

                client.aiAgent(AgentRequest.builder()
                                .contextId("ctx1")
                                .userQuestion("Hi")
                                .build());

                String body = server.takeRequest().getBody().readUtf8();
                assertFalse(body.contains("agent_options"));
                assertFalse(body.contains("credential_id"));
        }

        @Test
        public void testAiAgentSurfacesChipNotFoundOn404() throws Exception {
                server.enqueue(new MockResponse()
                                .setResponseCode(404)
                                .setHeader("Content-Type", "application/json")
                                .setBody("{\"error\":\"Chip 'lost' is not available (may have expired)\","
                                                + "\"error_code\":\"chip_not_found\",\"chip_id\":\"lost\"}"));

                try {
                        client.aiAgent(AgentRequest.builder()
                                        .contextId("ctx1")
                                        .userQuestion("Q")
                                        .build());
                        fail("Expected ChipNotFoundException");
                } catch (ChipNotFoundException e) {
                        assertEquals("lost", e.getChipId());
                }
        }

        @Test
        public void test500WithChipNotFoundCodeIsNotMisclassified() throws Exception {
                // Defense in depth: only 404 should be inspected for the typed code.
                server.enqueue(new MockResponse()
                                .setResponseCode(500)
                                .setHeader("Content-Type", "application/json")
                                .setBody("{\"error_code\":\"chip_not_found\",\"chip_id\":\"abc\"}"));

                try {
                        client.generateReport(Arrays.asList("abc"), Arrays.asList("SELECT 1"));
                        fail("Expected IOException");
                } catch (ChipNotFoundException e) {
                        fail("500 must not be classified as ChipNotFoundException");
                } catch (IOException expected) {
                        // OK
                }
        }
}