package com.datalathe.client;

import com.datalathe.client.types.IngestJobStatus;

import java.io.IOException;

/**
 * Thrown when an asynchronous ingest job reaches a terminal failure state
 * ({@code failed} or {@code cancelled}).
 *
 * <p>Recovery pattern: inspect {@link #getJobError()}; for resumable
 * failures call {@code resumeIngestJob} with the same job ID and wait
 * again.
 */
public class IngestFailedException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final IngestJobStatus status;
    private final String jobError;

    public IngestFailedException(String jobId, IngestJobStatus status, String jobError) {
        super("Ingest job " + jobId + " " + (status != null ? status.name().toLowerCase() : "failed")
                + (jobError != null ? ": " + jobError : ""));
        this.jobId = jobId;
        this.status = status;
        this.jobError = jobError;
    }

    public String getJobId() {
        return jobId;
    }

    public IngestJobStatus getStatus() {
        return status;
    }

    public String getJobError() {
        return jobError;
    }
}
