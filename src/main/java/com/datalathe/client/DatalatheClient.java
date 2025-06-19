package com.datalathe.client;

import com.datalathe.client.types.SourceType;
import com.datalathe.client.command.DatalatheCommand;
import com.datalathe.client.command.DatalatheCommandResponse;
import com.datalathe.client.command.impl.CreateChipCommand;
import com.datalathe.client.command.impl.GenerateReportCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;

@RequiredArgsConstructor
public class DatalatheClient {
    private static final Logger logger = LogManager.getLogger(DatalatheClient.class);
    private final String baseUrl;
    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

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
        CreateChipCommand.Request.Source source = new CreateChipCommand.Request.Source(sourceName, tableName, query);
        CreateChipCommand.Request request = new CreateChipCommand.Request(SourceType.MYSQL, source);
        request.setChipId(chipId);

        CreateChipCommand.Response response = sendCommand(new CreateChipCommand(request));
        if (response.getError() != null) {
            throw new IOException("Failed to stage data: " + response.getError());
        }
        return response.getChipId();
    }

    public String createChip(String sourceName, String query, String tableName) throws IOException {
        return createChips(
                Collections.singletonList(new CreateChipCommand.Request.Source(sourceName, tableName, query)), null)
                .get(0);
    }

    /**
     * Stages data from multiple source requests and returns a list of chip IDs
     * 
     * @param sourceRequests List of source requests to process
     * @param chipId         Optional chip ID to use
     * @return List of chip IDs
     * @throws IOException if any API call fails
     */
    public List<String> createChips(List<CreateChipCommand.Request.Source> sources, String chipId) throws IOException {
        CreateChipCommand.Request requestBase = new CreateChipCommand.Request();
        requestBase.setSourceType(SourceType.MYSQL);
        requestBase.setChipId(chipId);

        List<String> chipIds = new ArrayList<>();
        for (CreateChipCommand.Request.Source source : sources) {
            CreateChipCommand.Request request = new CreateChipCommand.Request(requestBase);
            request.setSource(source);
            CreateChipCommand.Response response = sendCommand(new CreateChipCommand(request));
            if (response.getError() != null) {
                throw new IOException("Failed to stage data: " + response.getError());
            }
            chipIds.add(response.getChipId());
        }
        return chipIds;
    }

    public List<String> createChips(List<CreateChipCommand.Request.Source> sources) throws IOException {
        return createChips(sources, null);
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

        logger.debug("Sending command: {}", httpRequest.url());
        logger.debug("Command: {}", objectMapper.writeValueAsString(command.getRequest()));

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
    public Map<Integer, GenerateReportCommand.Response.Result> generateReport(List<String> chipIds,
            List<String> queries) throws IOException {
        Map<Integer, GenerateReportCommand.Response.Result> results = new HashMap<>();

        GenerateReportCommand.Request request = new GenerateReportCommand.Request();
        request.setSourceType(SourceType.LOCAL);
        request.setQueryRequest(new GenerateReportCommand.Request.Queries(queries));
        request.setChipIds(chipIds);

        GenerateReportCommand.Response response = sendCommand(new GenerateReportCommand(request));

        if (response.getResult() != null) {
            for (Map.Entry<String, GenerateReportCommand.Response.Result> entry : response
                    .getResult().entrySet()) {
                int idx = Integer.parseInt(entry.getKey());
                GenerateReportCommand.Response.Result result = entry.getValue();
                results.put(idx, result);
            }
        }

        return results;
    }
}