package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lifecycle state of an asynchronous ingest job.
 */
public enum IngestJobStatus {
    @JsonProperty("queued") QUEUED,
    @JsonProperty("running") RUNNING,
    @JsonProperty("succeeded") SUCCEEDED,
    @JsonProperty("failed") FAILED,
    @JsonProperty("cancelled") CANCELLED;

    /**
     * True when the job will make no further progress
     * (succeeded, failed, or cancelled).
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
