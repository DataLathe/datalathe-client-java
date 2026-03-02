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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class DatalatheClient {
    private static final Logger logger = LogManager.getLogger(DatalatheClient.class);
    private final String baseUrl;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * Creates a chip from a source request
     *
     * @param sourceName The name of the source database
     * @param query      The SQL query to execute
     * @param tableName  The name of the table
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChip(String sourceName, String query, String tableName) throws IOException {
        return createChip(sourceName, query, tableName, null);
    }

    /**
     * Creates a chip from a source request with partition configuration
     *
     * @param sourceName The name of the source database
     * @param query      The SQL query to execute
     * @param tableName  The name of the table
     * @param partition  Optional partition configuration
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChip(String sourceName, String query, String tableName,
            CreateChipCommand.Request.Source.Partition partition) throws IOException {
        return createChip(sourceName, query, tableName, partition, null);
    }

    /**
     * Creates a chip from a source request with partition and column replace configuration
     *
     * @param sourceName    The name of the source database
     * @param query         The SQL query to execute
     * @param tableName     The name of the table
     * @param partition     Optional partition configuration
     * @param columnReplace Optional column rename map (old name → new name)
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChip(String sourceName, String query, String tableName,
            CreateChipCommand.Request.Source.Partition partition,
            Map<String, String> columnReplace) throws IOException {
        return createChip(sourceName, query, tableName, partition, columnReplace, null);
    }

    /**
     * Creates a chip from a source request with partition, column replace, and S3 storage configuration
     *
     * @param sourceName    The name of the source database
     * @param query         The SQL query to execute
     * @param tableName     The name of the table
     * @param partition     Optional partition configuration
     * @param columnReplace Optional column rename map (old name -> new name)
     * @param storageConfig Optional S3 storage configuration (bucket, prefix, TTL)
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChip(String sourceName, String query, String tableName,
            CreateChipCommand.Request.Source.Partition partition,
            Map<String, String> columnReplace,
            CreateChipCommand.S3StorageConfig storageConfig) throws IOException {
        CreateChipCommand.Request request = new CreateChipCommand.Request();
        request.setSourceType(SourceType.MYSQL);
        request.setSource(CreateChipCommand.Request.Source.builder()
                .databaseName(sourceName)
                .tableName(tableName)
                .query(query)
                .partition(partition)
                .columnReplace(columnReplace)
                .build());
        request.setStorageConfig(storageConfig);
        CreateChipCommand.Response response = sendCommand(new CreateChipCommand(request));
        if (response.getError() != null) {
            throw new IOException("Failed to stage data: " + response.getError());
        }
        return response.getChipId();
    }

    /**
     * Stages data from multiple source requests and returns a list of chip IDs
     * 
     * @param sources List of source requests to process
     * @param chipId  Optional chip ID to use
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

    /**
     * Creates a chip from a file source (CSV, Parquet, etc.)
     *
     * @param filePath  Path to the file on the server
     * @param tableName Optional table name for the chip
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromFile(String filePath, String tableName) throws IOException {
        return createChipFromFile(filePath, tableName, null);
    }

    /**
     * Creates a chip from a file source with partition configuration
     *
     * @param filePath  Path to the file on the server
     * @param tableName Optional table name for the chip
     * @param partition Optional partition configuration
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromFile(String filePath, String tableName,
            CreateChipCommand.Request.Source.Partition partition) throws IOException {
        return createChipFromFile(filePath, tableName, partition, null);
    }

    /**
     * Creates a chip from a file source with partition and column replace configuration
     *
     * @param filePath      Path to the file on the server
     * @param tableName     Optional table name for the chip
     * @param partition     Optional partition configuration
     * @param columnReplace Optional column rename map (old name → new name)
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromFile(String filePath, String tableName,
            CreateChipCommand.Request.Source.Partition partition,
            Map<String, String> columnReplace) throws IOException {
        return createChipFromFile(filePath, tableName, partition, columnReplace, null);
    }

    /**
     * Creates a chip from a file source with partition, column replace, and S3 storage configuration
     *
     * @param filePath      Path to the file on the server
     * @param tableName     Optional table name for the chip
     * @param partition     Optional partition configuration
     * @param columnReplace Optional column rename map (old name -> new name)
     * @param storageConfig Optional S3 storage configuration (bucket, prefix, TTL)
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromFile(String filePath, String tableName,
            CreateChipCommand.Request.Source.Partition partition,
            Map<String, String> columnReplace,
            CreateChipCommand.S3StorageConfig storageConfig) throws IOException {
        CreateChipCommand.Request request = new CreateChipCommand.Request();
        request.setSourceType(SourceType.FILE);
        request.setSource(CreateChipCommand.Request.Source.builder()
                .filePath(filePath)
                .tableName(tableName)
                .partition(partition)
                .columnReplace(columnReplace)
                .build());
        request.setStorageConfig(storageConfig);
        CreateChipCommand.Response response = sendCommand(new CreateChipCommand(request));
        if (response.getError() != null) {
            throw new IOException("Failed to stage file: " + response.getError());
        }
        return response.getChipId();
    }

    /**
     * Creates a new chip from existing chip(s) as the data source.
     *
     * @param sourceChipIds The chip ID(s) to use as source data
     * @param query         Optional SQL query to transform the data (runs against source chip tables)
     * @param tableName     Optional table name for the new chip
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromChip(List<String> sourceChipIds, String query, String tableName) throws IOException {
        return createChipFromChip(sourceChipIds, query, tableName, null);
    }

    /**
     * Creates a new chip from existing chip(s) with S3 storage configuration.
     *
     * @param sourceChipIds The chip ID(s) to use as source data
     * @param query         Optional SQL query to transform the data (runs against source chip tables)
     * @param tableName     Optional table name for the new chip
     * @param storageConfig Optional S3 storage configuration (bucket, prefix, TTL)
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromChip(List<String> sourceChipIds, String query, String tableName,
            CreateChipCommand.S3StorageConfig storageConfig) throws IOException {
        CreateChipCommand.Request request = new CreateChipCommand.Request();
        request.setSourceType(SourceType.CACHE);
        request.setSource(CreateChipCommand.Request.Source.builder()
                .sourceChipIds(sourceChipIds)
                .query(query)
                .tableName(tableName)
                .build());
        request.setStorageConfig(storageConfig);
        CreateChipCommand.Response response = sendCommand(new CreateChipCommand(request));
        if (response.getError() != null) {
            throw new IOException("Failed to create chip from chip: " + response.getError());
        }
        return response.getChipId();
    }

    /**
     * Deletes a chip and its associated data (local files and S3 objects).
     *
     * @param chipId The ID of the chip to delete
     * @throws IOException if the API call fails
     */
    public void deleteChip(String chipId) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/lathe/chips/" + URLEncoder.encode(chipId, StandardCharsets.UTF_8))
                .delete()
                .build();

        logger.debug("Deleting chip: {}", chipId);

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to delete chip: " + response.code() + " " + response.body().string());
            }
        }
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
     * @return Map of query index to Result
     * @throws IOException if the API call fails
     */
    public Map<Integer, GenerateReportCommand.Response.Result> generateReport(List<String> chipIds,
            List<String> queries) throws IOException {
        return generateReport(chipIds, queries, null, null).getResults();
    }

    /**
     * Executes queries against a list of chip IDs with transform query support
     *
     * @param chipIds               List of chip IDs to query
     * @param queries               List of SQL queries to execute
     * @param transformQuery        If true, transform queries from MariaDB to DuckDB syntax
     * @param returnTransformedQuery If true, include the transformed query in results
     * @return GenerateReportResult containing results map and timing metadata
     * @throws IOException if the API call fails
     */
    public GenerateReportResult generateReport(List<String> chipIds, List<String> queries,
            Boolean transformQuery, Boolean returnTransformedQuery) throws IOException {
        GenerateReportCommand.Request request = new GenerateReportCommand.Request();
        request.setSourceType(SourceType.LOCAL);
        request.setQueryRequest(new GenerateReportCommand.Request.Queries(queries));
        request.setChipIds(chipIds);
        request.setTransformQuery(transformQuery);
        request.setReturnTransformedQuery(returnTransformedQuery);

        GenerateReportCommand.Response response = sendCommand(new GenerateReportCommand(request));

        Map<Integer, GenerateReportCommand.Response.Result> results = new HashMap<>();
        if (response.getResult() != null) {
            for (Map.Entry<String, GenerateReportCommand.Response.Result> entry : response
                    .getResult().entrySet()) {
                int idx = Integer.parseInt(entry.getKey());
                GenerateReportCommand.Response.Result result = entry.getValue();
                results.put(idx, result);
            }
        }

        return new GenerateReportResult(results, response.getTiming());
    }
}