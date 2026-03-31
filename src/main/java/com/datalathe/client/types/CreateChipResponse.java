package com.datalathe.client.types;

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
}
