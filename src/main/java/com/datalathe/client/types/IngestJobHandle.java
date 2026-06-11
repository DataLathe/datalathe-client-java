package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Returned when an asynchronous ingest is accepted (HTTP 202). Use the
 * job ID with {@code getIngestJob} / {@code waitForIngest} to track
 * progress; the chip ID identifies the chip being created.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IngestJobHandle {
    @JsonProperty("job_id")
    private String jobId;

    @JsonProperty("chip_id")
    private String chipId;
}
