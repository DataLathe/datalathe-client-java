package com.datalathe.client.command.impl;

import com.datalathe.client.command.DatalatheCommand;
import com.datalathe.client.command.DatalatheCommandResponse;
import com.datalathe.client.model.StageDataRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CreateChipCommand implements DatalatheCommand {
    private final StageDataRequest request;

    public CreateChipCommand(StageDataRequest request) {
        this.request = request;
    }

    @Override
    public String getEndpoint() {
        return "/lathe/stage/data";
    }

    @Override
    public DatalatheCommandResponse getResponseType() {
        return new CreateChipResponse();
    }

    public StageDataRequest getRequest() {
        return request;
    }

    public static class CreateChipResponse implements DatalatheCommandResponse {

        @JsonProperty("chip_id")
        private String chipId;

        @JsonProperty("error")
        private String error;

        public CreateChipResponse() {
        }

        public String getChipId() {
            return chipId;
        }

        public void setChipId(String chipId) {
            this.chipId = chipId;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }
}
