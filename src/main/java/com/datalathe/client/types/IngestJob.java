package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Status record for an asynchronous ingest job. All fields except
 * {@code jobId} and {@code status} may be null.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IngestJob {
    @JsonProperty("job_id")
    private String jobId;

    @JsonProperty("chip_id")
    private String chipId;

    @JsonProperty("status")
    private IngestJobStatus status;

    @JsonProperty("rows_ingested")
    private Long rowsIngested;

    @JsonProperty("chunks_done")
    private Integer chunksDone;

    @JsonProperty("chunks_total")
    private Integer chunksTotal;

    @JsonProperty("error")
    private String error;

    @JsonProperty("created_at")
    private Long createdAt;

    @JsonProperty("updated_at")
    private Long updatedAt;
}
