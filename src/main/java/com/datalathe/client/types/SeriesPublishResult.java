package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The result of publishing a held chip into its series. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeriesPublishResult {
    @JsonProperty("series_id")
    private String seriesId;

    @JsonProperty("version")
    private Long version;

    /** {@code published}, or {@code already_published} for a repeated call. */
    @JsonProperty("outcome")
    private String outcome;
}
