package com.datalathe.client.command.impl;

import com.datalathe.client.command.DatalatheCommand;
import com.datalathe.client.command.DatalatheCommandResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class ExtractTablesCommand implements DatalatheCommand {
    private final Request request;

    public ExtractTablesCommand(String query) {
        this.request = new Request(query, null);
    }

    public ExtractTablesCommand(String query, Boolean transform) {
        this.request = new Request(query, transform);
    }

    @Override
    public String getEndpoint() {
        return "/lathe/query/tables";
    }

    @Override
    public Request getRequest() {
        return request;
    }

    @Override
    public DatalatheCommandResponse getResponseType() {
        return new Response();
    }

    @Data
    @NoArgsConstructor
    public static class Request {
        @JsonProperty("query")
        private String query;

        @JsonProperty("transform")
        private Boolean transform;

        public Request(String query, Boolean transform) {
            this.query = query;
            this.transform = transform;
        }
    }

    @Data
    @NoArgsConstructor
    public static class Response implements DatalatheCommandResponse {
        @JsonProperty("tables")
        private List<String> tables;

        @JsonProperty("transformed_query")
        private String transformedQuery;

        @JsonProperty("error")
        private String error;
    }
}
