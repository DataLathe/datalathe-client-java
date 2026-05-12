package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateChipResponse {
    @JsonProperty("chip_id")
    private String chipId;

    @JsonProperty("error")
    private String error;

    /**
     * Total rows ingested. Present on successful streaming ingest responses; absent otherwise.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("total_rows")
    private Long totalRows;

    /**
     * Wall-clock ingest duration in milliseconds. Present on successful streaming ingest
     * responses; absent otherwise.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("elapsed_ms")
    private Long elapsedMs;
}
