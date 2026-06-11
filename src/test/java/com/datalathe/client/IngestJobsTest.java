package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datalathe.client.types.ChipSource;
import com.datalathe.client.types.IngestJob;
import com.datalathe.client.types.IngestJobHandle;
import com.datalathe.client.types.IngestJobStatus;
import com.datalathe.client.types.SourceType;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IngestJobsTest {

    private MockWebServer server;
    private DatalatheClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void enqueueJson(int code, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private static String jobBody(String status, String error) {
        return "{\"job_id\":\"job-1\",\"chip_id\":\"chip-1\",\"status\":\"" + status + "\","
                + "\"rows_ingested\":500,\"chunks_done\":5,\"chunks_total\":10,"
                + "\"error\":" + (error == null ? "null" : "\"" + error + "\"") + ","
                + "\"created_at\":1700000000,\"updated_at\":1700000100}";
    }

    @Test
    void createChipAsyncSubmitsJobAndReturnsHandle() throws Exception {
        enqueueJson(202, "{\"job_id\":\"job-1\",\"chip_id\":\"chip-1\"}");

        ChipSource source = ChipSource.builder()
                .sourceType(SourceType.MYSQL)
                .databaseName("test_db")
                .tableName("users")
                .query("SELECT * FROM users")
                .build();
        IngestJobHandle handle = client.createChipAsync(source);

        assertEquals("job-1", handle.getJobId());
        assertEquals("chip-1", handle.getChipId());

        RecordedRequest request = server.takeRequest();
        assertEquals("/lathe/stage/data", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"async\":true"));
        assertTrue(body.contains("\"source_type\":\"MYSQL\""));
        assertTrue(body.contains("\"table_name\":\"users\""));
    }

    @Test
    void createChipAsyncRejectsSourceWithoutSourceType() {
        ChipSource source = ChipSource.builder().tableName("users").build();
        assertThrows(IllegalArgumentException.class, () -> client.createChipAsync(source));
    }

    @Test
    void createChipSyncRequestOmitsAsyncFlag() throws Exception {
        enqueueJson(200, "{\"chip_id\":\"chip-1\",\"error\":null}");

        client.createChip("test_db", "SELECT * FROM users", "users");

        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(!body.contains("\"async\""));
    }

    @Test
    void getIngestJobParsesFullRecord() throws Exception {
        enqueueJson(200, jobBody("running", null));

        IngestJob job = client.getIngestJob("job-1");

        assertEquals("/lathe/jobs/job-1", server.takeRequest().getPath());
        assertEquals("job-1", job.getJobId());
        assertEquals("chip-1", job.getChipId());
        assertEquals(IngestJobStatus.RUNNING, job.getStatus());
        assertEquals(Long.valueOf(500), job.getRowsIngested());
        assertEquals(Integer.valueOf(5), job.getChunksDone());
        assertEquals(Integer.valueOf(10), job.getChunksTotal());
        assertNull(job.getError());
        assertEquals(Long.valueOf(1700000000L), job.getCreatedAt());
        assertEquals(Long.valueOf(1700000100L), job.getUpdatedAt());
    }

    @Test
    void listIngestJobsSendsStatusFilter() throws Exception {
        enqueueJson(200, "[" + jobBody("failed", "boom") + "]");

        List<IngestJob> jobs = client.listIngestJobs("failed");

        assertEquals("/lathe/jobs?status=failed", server.takeRequest().getPath());
        assertEquals(1, jobs.size());
        assertEquals(IngestJobStatus.FAILED, jobs.get(0).getStatus());
        assertEquals("boom", jobs.get(0).getError());
    }

    @Test
    void listIngestJobsWithoutFilterKeepsBarePath() throws Exception {
        enqueueJson(200, "[]");

        List<IngestJob> jobs = client.listIngestJobs();

        assertEquals("/lathe/jobs", server.takeRequest().getPath());
        assertTrue(jobs.isEmpty());
    }

    @Test
    void resumeIngestJobPostsToResumeEndpoint() throws Exception {
        enqueueJson(202, "{\"job_id\":\"job-1\",\"chip_id\":\"chip-1\"}");

        IngestJobHandle handle = client.resumeIngestJob("job-1");

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/lathe/jobs/job-1/resume", request.getPath());
        assertEquals("job-1", handle.getJobId());
    }

    @Test
    void resumeIngestJobSurfacesConflict() {
        enqueueJson(409, "{\"error\":\"job is still running\"}");

        IOException e = assertThrows(IOException.class, () -> client.resumeIngestJob("job-1"));
        assertTrue(e.getMessage().contains("409"));
    }

    @Test
    void waitForIngestPollsUntilSucceeded() throws Exception {
        enqueueJson(200, jobBody("queued", null));
        enqueueJson(200, jobBody("running", null));
        enqueueJson(200, jobBody("succeeded", null));

        IngestJob job = client.waitForIngest("job-1", Duration.ofMillis(10), Duration.ofSeconds(5));

        assertEquals(IngestJobStatus.SUCCEEDED, job.getStatus());
        assertEquals(Long.valueOf(500), job.getRowsIngested());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void waitForIngestThrowsTypedExceptionOnFailure() {
        enqueueJson(200, jobBody("failed", "source connection lost"));

        IngestFailedException e = assertThrows(IngestFailedException.class,
                () -> client.waitForIngest("job-1", Duration.ofMillis(10), Duration.ofSeconds(5)));

        assertEquals("job-1", e.getJobId());
        assertEquals(IngestJobStatus.FAILED, e.getStatus());
        assertEquals("source connection lost", e.getJobError());
        assertTrue(e.getMessage().contains("source connection lost"));
    }

    @Test
    void waitForIngestThrowsTypedExceptionOnCancelled() {
        enqueueJson(200, jobBody("cancelled", null));

        IngestFailedException e = assertThrows(IngestFailedException.class,
                () -> client.waitForIngest("job-1", Duration.ofMillis(10), Duration.ofSeconds(5)));

        assertEquals(IngestJobStatus.CANCELLED, e.getStatus());
        assertNull(e.getJobError());
    }

    @Test
    void waitForIngestThrowsOnTimeout() {
        enqueueJson(200, jobBody("running", null));

        IOException e = assertThrows(IOException.class,
                () -> client.waitForIngest("job-1", Duration.ofMillis(10), Duration.ZERO));

        assertTrue(e.getMessage().contains("Timed out waiting for ingest job job-1"));
    }
}
