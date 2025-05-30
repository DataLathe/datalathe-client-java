package com.datalathe.client;

import com.datalathe.client.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

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
     * Stages data from multiple SQL queries and returns an array of chip IDs
     * 
     * @param sourceName The name of the source
     * @param queries    List of SQL queries to execute
     * @return List of chip IDs
     * @throws IOException if the API call fails
     */
    public List<String> stageData(String sourceName, List<String> queries, String tableName) throws IOException {
        return queries.stream().map(query -> {
            StageDataSourceRequest sourceRequest = new StageDataSourceRequest();
            sourceRequest.setQuery(query);
            sourceRequest.setDatabaseName(sourceName);
            sourceRequest.setTableName(tableName);

            StageDataRequest request = new StageDataRequest(SourceType.MYSQL, sourceRequest);

            try {
                Request httpRequest = new Request.Builder()
                        .url(baseUrl + "/lathe/stage/data")
                        .post(RequestBody.create(objectMapper.writeValueAsString(request), JSON))
                        .build();

                try (Response response = client.newCall(httpRequest).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("Failed to stage data: " + response.code());
                    }

                    JsonNode responseJson = objectMapper.readTree(response.body().string());
                    if (responseJson.has("chip_id")) {
                        return responseJson.get("chip_id").asText();
                    }
                    throw new IOException("No chip_id in response");
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to stage data: " + e.getMessage());
            }
        }).collect(Collectors.toList());
    }

    /**
     * Executes queries against a list of chip IDs and returns the results as JDBC
     * ResultSets
     * 
     * @param chipIds List of chip IDs to query
     * @param queries List of SQL queries to execute
     * @return Map of query index to ResultSet
     * @throws IOException if the API call fails
     */
    public Map<Integer, ResultSet> query(List<String> chipIds, List<String> queries) throws IOException {
        Map<Integer, ResultSet> results = new HashMap<>();

        SourceRequest sourceRequest = new SourceRequest(queries);
        ReportRequest request = new ReportRequest(chipIds, SourceType.LOCAL, sourceRequest);

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/lathe/report")
                .post(RequestBody.create(objectMapper.writeValueAsString(request), JSON))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to generate report: " + response.code());
            }

            String responseBody = response.body().string();
            ReportResponse reportResponse = objectMapper.readValue(responseBody, ReportResponse.class);

            if (reportResponse.getResult() != null) {
                for (Map.Entry<String, ReportResponse.GenericResult> entry : reportResponse.getResult().entrySet()) {
                    int idx = Integer.parseInt(entry.getKey());
                    ReportResponse.GenericResult result = entry.getValue();
                    if (result.getError() == null) {
                        results.put(idx, new DatalatheResultSet(result));
                    }
                }
            }
        }

        return results;
    }
}