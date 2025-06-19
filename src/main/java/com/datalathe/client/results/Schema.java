package com.datalathe.client.results;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Schema {
    @JsonProperty("name")
    private String name;

    @JsonProperty("data_type")
    private String dataType;

}