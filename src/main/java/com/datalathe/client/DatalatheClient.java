package com.datalathe.client;

import com.datalathe.client.model.*;
import com.datalathe.client.command.DatalatheCommand;
import com.datalathe.client.command.DatalatheCommandResponse;
import com.datalathe.client.command.impl.CreateChipCommand;
import com.datalathe.client.command.impl.GenerateReportCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.*;

public class DatalatheClient {
    private final String baseUrl;
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public DatalatheClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Stages data from a SQL query and returns a chip ID
     * 
     * @param sourceName The name of the source database
     * @param query      The SQL query to execute
     * @param tableName  The name of the table
     * @param chipId     Optional chip ID to use
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String stageData(String sourceName, String query, String tableName, String chipId)
            throws IOException {
        StageDataSourceRequest sourceRequest = new StageDataSourceRequest(sourceName, tableName, query);
        StageDataRequest request = new StageDataRequest(SourceType.MYSQL, sourceRequest);
        request.setChipId(chipId);

        CreateChipCommand.CreateChipResponse response = sendCommand(new CreateChipCommand(request));
        if (response.getError() != null) {
            throw new IOException("Failed to stage data: " + response.getError());
        }
        return response.getChipId();
    }

    public String stageData(String sourceName, String query, String tableName) throws IOException {
        return stageData(sourceName, query, tableName, null);
    }

    /**
     * Stages data from multiple source requests and returns a list of chip IDs
     * 
     * @param sourceRequests List of source requests to process
     * @param chipId         Optional chip ID to use
     * @return List of chip IDs
     * @throws IOException if any API call fails
     */
    public List<String> stageData(List<StageDataSourceRequest> sourceRequests, String chipId) throws IOException {
        StageDataRequest requestBase = new StageDataRequest();
        requestBase.setSourceType(SourceType.MYSQL);
        requestBase.setChipId(chipId);

        List<String> chipIds = new ArrayList<>();
        for (StageDataSourceRequest sourceRequest : sourceRequests) {
            StageDataRequest request = new StageDataRequest(requestBase);
            request.setSourceRequest(sourceRequest);
            CreateChipCommand.CreateChipResponse response = sendCommand(new CreateChipCommand(request));
            if (response.getError() != null) {
                throw new IOException("Failed to stage data: " + response.getError());
            }
            chipIds.add(response.getChipId());
        }
        return chipIds;
    }

    public List<String> stageData(List<StageDataSourceRequest> sourceRequests) throws IOException {
        return stageData(sourceRequests, null);
    }

    /**
     * Sends a command to the Datalathe API
     * 
     * @param command The command to send
     * @return The response from the API
     * @throws IOException if the API call fails
     */
    public <T extends DatalatheCommandResponse> T sendCommand(DatalatheCommand command) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(baseUrl + command.getEndpoint())
                .post(RequestBody.create(objectMapper.writeValueAsString(command.getRequest()), JSON))
                .build();

        System.out.println("Sending command: " + httpRequest.url());
        System.out.println("Command: " + objectMapper.writeValueAsString(command.getRequest()));

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to execute command: " + response.code() + " " + response.body().string());
            }

            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody,
                    objectMapper.constructType(command.getResponseType().getClass()));
        }
    }

    /**
     * Executes queries against a list of chip IDs
     * 
     * @param chipIds List of chip IDs to query
     * @param queries List of SQL queries to execute
     * @return Map of query index to ResultSet
     * @throws IOException if the API call fails
     */
    public Map<Integer, ResultSet> query(List<String> chipIds, List<String> queries) throws IOException {
        Map<Integer, ResultSet> results = new HashMap<>();

        ReportRequest request = new ReportRequest(chipIds, SourceType.LOCAL, new SourceRequest(queries));
        GenerateReportCommand.GenerateReportResponse response = sendCommand(new GenerateReportCommand(request));

        if (response.getResult() != null) {
            for (Map.Entry<String, GenerateReportCommand.GenerateReportResponse.GenericResult> entry : response
                    .getResult().entrySet()) {
                int idx = Integer.parseInt(entry.getKey());
                GenerateReportCommand.GenerateReportResponse.GenericResult result = entry.getValue();
                if (result.getError() == null) {
                    results.put(idx, new DatalatheResultSet(result));
                } else {
                    System.out.println("Error: " + result.getError());
                }

            }
        }

        return results;
    }
}