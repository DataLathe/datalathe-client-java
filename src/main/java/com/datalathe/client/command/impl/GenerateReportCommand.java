package com.datalathe.client.command.impl;

import com.datalathe.client.command.DatalatheCommand;
import com.datalathe.client.command.DatalatheCommandResponse;
import com.datalathe.client.model.DatalatheResultSet;
import com.datalathe.client.model.ReportRequest;
import com.datalathe.client.model.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

public class GenerateReportCommand implements DatalatheCommand {
    private final ReportRequest request;

    public GenerateReportCommand(ReportRequest request) {
        this.request = request;
    }

    @Override
    public String getEndpoint() {
        return "/lathe/report";
    }

    @Override
    public Object getRequest() {
        return request;
    }

    @Override
    public DatalatheCommandResponse getResponseType() {
        return new Response();
    }

    public static class Response implements DatalatheCommandResponse {
        @JsonProperty("result")
        private Map<String, Result> result;

        public Map<String, Result> getResult() {
            return result;
        }

        public void setResult(Map<String, Result> result) {
            this.result = result;
        }

        public static class Result {
            @JsonProperty("error")
            private String error;

            @JsonProperty("data")
            private List<List<String>> data;

            @JsonProperty("result")
            private List<List<String>> result;

            @JsonProperty("schema")
            private List<Schema> schema;

            @JsonProperty("idx")
            private String idx;

            public String getError() {
                return error;
            }

            public void setError(String error) {
                this.error = error;
            }

            public List<List<String>> getData() {
                return data;
            }

            public void setData(List<List<String>> data) {
                this.data = data;
            }

            public List<List<String>> getResult() {
                return result;
            }

            public void setResult(List<List<String>> result) {
                this.result = result;
            }

            public List<Schema> getSchema() {
                return schema;
            }

            public void setSchema(List<Schema> schema) {
                this.schema = schema;
            }

            public String getIdx() {
                return idx;
            }

            public void setIdx(String idx) {
                this.idx = idx;
            }

            public ResultSet getResultSet() {
                return new DatalatheResultSet(this);
            }
        }
    }
}