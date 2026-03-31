package com.datalathe.client;

import com.datalathe.client.types.*;
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
            ChipSource.Partition partition) throws IOException {
        return createChip(sourceName, query, tableName, partition, null);
    }

    /**
     * Creates a chip from a source request with partition and column replace
     * configuration
     *
     * @param sourceName    The name of the source database
     * @param query         The SQL query to execute
     * @param tableName     The name of the table
     * @param partition     Optional partition configuration
     * @param columnReplace Optional column rename map (old name -> new name)
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChip(String sourceName, String query, String tableName,
            ChipSource.Partition partition,
            Map<String, String> columnReplace) throws IOException {
        return createChip(sourceName, query, tableName, partition, columnReplace, null);
    }

    /**
     * Creates a chip from a source request with partition, column replace, and S3
     * storage configuration
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
            ChipSource.Partition partition,
            Map<String, String> columnReplace,
            S3StorageConfig storageConfig) throws IOException {
        CreateChipRequest request = new CreateChipRequest();
        request.setSourceType(SourceType.MYSQL);
        request.setSource(ChipSource.builder()
                .databaseName(sourceName)
                .tableName(tableName)
                .query(query)
                .partition(partition)
                .columnReplace(columnReplace)
                .build());
        request.setStorageConfig(storageConfig);
        CreateChipResponse response = post("/lathe/stage/data", request, CreateChipResponse.class);
        if (response.getError() != null) {
            throw new IOException("Failed to stage data: " + response.getError());
        }
        return response.getChipId();
    }

    /**
     * Creates a chip from a pre-built Source object.
     * The Source must have sourceType set. If storageConfig is set on the Source,
     * it will be used.
     *
     * @param source The fully configured source
     * @return The chip ID
     * @throws IOException              if the API call fails
     * @throws IllegalArgumentException if sourceType is not set on the source
     */
    public String createChip(ChipSource source) throws IOException {
        return createChip(source, null);
    }

    /**
     * Creates a chip from a pre-built Source object with an optional chip ID.
     * The Source must have sourceType set. If storageConfig is set on the Source,
     * it will be used.
     *
     * @param source The fully configured source
     * @param chipId Optional chip ID to use
     * @return The chip ID
     * @throws IOException              if the API call fails
     * @throws IllegalArgumentException if sourceType is not set on the source
     */
    public String createChip(ChipSource source, String chipId) throws IOException {
        if (source.getSourceType() == null) {
            throw new IllegalArgumentException("sourceType must be set on the Source");
        }
        CreateChipRequest request = new CreateChipRequest();
        request.setSourceType(source.getSourceType());
        request.setSource(source);
        request.setChipId(chipId);
        request.setStorageConfig(source.getStorageConfig());
        CreateChipResponse response = post("/lathe/stage/data", request, CreateChipResponse.class);
        if (response.getError() != null) {
            throw new IOException("Failed to stage data: " + response.getError());
        }
        return response.getChipId();
    }

    /**
     * Creates chips from a list of pre-built Source objects.
     * Each Source must have sourceType set. If storageConfig is set on a Source, it
     * will be used.
     *
     * @param sources List of fully configured sources
     * @return List of chip IDs
     * @throws IOException              if any API call fails
     * @throws IllegalArgumentException if sourceType is not set on any source
     */
    public List<String> createChips(List<ChipSource> sources) throws IOException {
        return createChips(sources, null);
    }

    /**
     * Creates chips from a list of pre-built Source objects with an optional shared
     * chip ID.
     * Each Source must have sourceType set. If storageConfig is set on a Source, it
     * will be used.
     *
     * @param sources List of fully configured sources
     * @param chipId  Optional chip ID to use for all sources
     * @return List of chip IDs
     * @throws IOException              if any API call fails
     * @throws IllegalArgumentException if sourceType is not set on any source
     */
    public List<String> createChips(List<ChipSource> sources, String chipId) throws IOException {
        List<String> chipIds = new ArrayList<>();
        for (ChipSource source : sources) {
            if (source.getSourceType() == null) {
                throw new IllegalArgumentException("sourceType must be set on each Source");
            }
            CreateChipRequest request = new CreateChipRequest();
            request.setSourceType(source.getSourceType());
            request.setSource(source);
            request.setChipId(chipId);
            request.setStorageConfig(source.getStorageConfig());
            CreateChipResponse response = post("/lathe/stage/data", request, CreateChipResponse.class);
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
            ChipSource.Partition partition) throws IOException {
        return createChipFromFile(filePath, tableName, partition, null);
    }

    /**
     * Creates a chip from a file source with partition and column replace
     * configuration
     *
     * @param filePath      Path to the file on the server
     * @param tableName     Optional table name for the chip
     * @param partition     Optional partition configuration
     * @param columnReplace Optional column rename map (old name -> new name)
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromFile(String filePath, String tableName,
            ChipSource.Partition partition,
            Map<String, String> columnReplace) throws IOException {
        return createChipFromFile(filePath, tableName, partition, columnReplace, null);
    }

    /**
     * Creates a chip from a file source with partition, column replace, and S3
     * storage configuration
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
            ChipSource.Partition partition,
            Map<String, String> columnReplace,
            S3StorageConfig storageConfig) throws IOException {
        CreateChipRequest request = new CreateChipRequest();
        request.setSourceType(SourceType.FILE);
        request.setSource(ChipSource.builder()
                .filePath(filePath)
                .tableName(tableName)
                .partition(partition)
                .columnReplace(columnReplace)
                .build());
        request.setStorageConfig(storageConfig);
        CreateChipResponse response = post("/lathe/stage/data", request, CreateChipResponse.class);
        if (response.getError() != null) {
            throw new IOException("Failed to stage file: " + response.getError());
        }
        return response.getChipId();
    }

    /**
     * Creates a new chip from an S3 object (CSV, Parquet, etc.).
     *
     * @param s3Path    S3 URI (e.g. s3://bucket/path/file.csv)
     * @param tableName Optional table name for the chip
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromS3(String s3Path, String tableName) throws IOException {
        return createChipFromS3(s3Path, tableName, null, null);
    }

    /**
     * Creates a new chip from an S3 object with full options.
     *
     * @param s3Path        S3 URI (e.g. s3://bucket/path/file.csv)
     * @param tableName     Optional table name for the chip
     * @param columnReplace Optional column renaming map
     * @param storageConfig Optional S3 storage configuration for the created chip
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromS3(String s3Path, String tableName,
            Map<String, String> columnReplace,
            S3StorageConfig storageConfig) throws IOException {
        CreateChipRequest request = new CreateChipRequest();
        request.setSourceType(SourceType.S3);
        request.setSource(ChipSource.builder()
                .s3Path(s3Path)
                .tableName(tableName)
                .columnReplace(columnReplace)
                .build());
        request.setStorageConfig(storageConfig);
        CreateChipResponse response = post("/lathe/stage/data", request, CreateChipResponse.class);
        if (response.getError() != null) {
            throw new IOException("Failed to create chip from S3: " + response.getError());
        }
        return response.getChipId();
    }

    /**
     * Creates a new chip from existing chip(s) as the data source.
     *
     * @param sourceChipIds The chip ID(s) to use as source data
     * @param query         Optional SQL query to transform the data (runs against
     *                      source chip tables)
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
     * @param query         Optional SQL query to transform the data (runs against
     *                      source chip tables)
     * @param tableName     Optional table name for the new chip
     * @param storageConfig Optional S3 storage configuration (bucket, prefix, TTL)
     * @return The chip ID
     * @throws IOException if the API call fails
     */
    public String createChipFromChip(List<String> sourceChipIds, String query, String tableName,
            S3StorageConfig storageConfig) throws IOException {
        CreateChipRequest request = new CreateChipRequest();
        request.setSourceType(SourceType.CHIP);
        request.setSource(ChipSource.builder()
                .sourceChipIds(sourceChipIds)
                .query(query)
                .tableName(tableName)
                .build());
        request.setStorageConfig(storageConfig);
        CreateChipResponse response = post("/lathe/stage/data", request, CreateChipResponse.class);
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
        httpDelete("/lathe/chips/" + URLEncoder.encode(chipId, StandardCharsets.UTF_8));
    }

    // --- Connection management ---

    /**
     * Lists all database connections (passwords excluded).
     */
    public List<ConnectionInfo> listConnections() throws IOException {
        return Arrays.asList(get("/lathe/connections", ConnectionInfo[].class));
    }

    /**
     * Creates or updates a database connection.
     */
    public ConnectionInfo upsertConnection(String alias, String host, String port, String database, String user,
            String password) throws IOException {
        Map<String, String> body = new HashMap<>();
        body.put("host", host);
        body.put("port", port);
        body.put("database", database);
        body.put("user", user);
        body.put("password", password);

        return put("/lathe/connections/" + URLEncoder.encode(alias, StandardCharsets.UTF_8), body, ConnectionInfo.class);
    }

    /**
     * Deletes a database connection.
     */
    public void deleteConnection(String alias) throws IOException {
        httpDelete("/lathe/connections/" + URLEncoder.encode(alias, StandardCharsets.UTF_8));
    }

    /**
     * Tests a database connection by attempting a MySQL attach in DuckDB.
     */
    public ConnectionInfo testConnection(String alias) throws IOException {
        return post("/lathe/connections/" + URLEncoder.encode(alias, StandardCharsets.UTF_8) + "/test",
                new HashMap<>(), ConnectionInfo.class);
    }

    // --- Database inspection ---

    /**
     * Returns the list of databases available in the engine.
     */
    public List<DatabaseInfo> getDatabases() throws IOException {
        return Arrays.asList(get("/lathe/stage/databases", DatabaseInfo[].class));
    }

    /**
     * Returns the schema (tables and columns) for a given database.
     */
    public List<DatabaseColumn> getDatabaseSchema(String databaseName) throws IOException {
        return Arrays.asList(get("/lathe/stage/schema/" + URLEncoder.encode(databaseName, StandardCharsets.UTF_8), DatabaseColumn[].class));
    }

    // --- Chip listing ---

    /**
     * Returns all chips and their metadata.
     */
    public SearchChipsResponse listChips() throws IOException {
        return get("/lathe/chips", SearchChipsResponse.class);
    }

    /**
     * Gets a database connection by alias (password excluded).
     */
    public ConnectionInfo getConnection(String alias) throws IOException {
        return get("/lathe/connections/" + URLEncoder.encode(alias, StandardCharsets.UTF_8), ConnectionInfo.class);
    }

    // --- License management ---

    /**
     * Gets the current license status.
     */
    public LicenseStatus getLicense() throws IOException {
        return get("/lathe/license", LicenseStatus.class);
    }

    /**
     * Installs or updates the license key.
     */
    public LicenseStatus putLicense(String licenseKey) throws IOException {
        Map<String, String> body = new HashMap<>();
        body.put("license_key", licenseKey);
        return put("/lathe/license", body, LicenseStatus.class);
    }

    /**
     * Searches for chips by table name and/or partition value.
     *
     * @param tableName      Optional table name filter
     * @param partitionValue Optional partition value filter
     * @return The search response containing matched chips and metadata
     * @throws IOException if the API call fails
     */
    public SearchChipsResponse searchChips(String tableName, String partitionValue) throws IOException {
        return searchChips(tableName, partitionValue, null, null);
    }

    /**
     * Searches for chips by table name, partition value, and/or tag.
     *
     * @param tableName      Optional table name filter
     * @param partitionValue Optional partition value filter
     * @param tagKey         Tag key to filter by (requires tagValue)
     * @param tagValue       Tag value to filter by (requires tagKey)
     * @return The search response containing matched chips, metadata, and tags
     * @throws IOException if the API call fails
     */
    public SearchChipsResponse searchChips(String tableName, String partitionValue,
            String tagKey, String tagValue) throws IOException {
        String tag = (tagKey != null && tagValue != null) ? tagKey + ":" + tagValue : null;

        StringBuilder url = new StringBuilder(baseUrl).append("/lathe/chips/search");
        List<String> params = new ArrayList<>();
        if (tableName != null) {
            params.add("table_name=" + URLEncoder.encode(tableName, StandardCharsets.UTF_8));
        }
        if (partitionValue != null) {
            params.add("partition_value=" + URLEncoder.encode(partitionValue, StandardCharsets.UTF_8));
        }
        if (tag != null) {
            params.add("tag=" + URLEncoder.encode(tag, StandardCharsets.UTF_8));
        }
        if (!params.isEmpty()) {
            url.append("?").append(String.join("&", params));
        }

        Request httpRequest = new Request.Builder()
                .url(url.toString())
                .get()
                .build();

        logger.debug("Searching chips: {}", httpRequest.url());

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to search chips: " + response.code() + " " + response.body().string());
            }
            return objectMapper.readValue(response.body().string(), SearchChipsResponse.class);
        }
    }

    /**
     * Adds or updates tags on a chip. Existing keys have their values replaced.
     *
     * @param chipId The chip ID to tag
     * @param tags   Key-value pairs to set
     * @throws IOException if the API call fails
     */
    public void addChipTags(String chipId, Map<String, String> tags) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("tags", tags);

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/lathe/chips/" + URLEncoder.encode(chipId, StandardCharsets.UTF_8) + "/tags")
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                .build();

        logger.debug("Adding tags to chip: {}", chipId);

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to add chip tags: " + response.code() + " " + response.body().string());
            }
        }
    }

    /**
     * Removes a tag from a chip by key.
     *
     * @param chipId The chip ID
     * @param key    The tag key to remove
     * @throws IOException if the API call fails
     */
    public void deleteChipTag(String chipId, String key) throws IOException {
        httpDelete("/lathe/chips/" + URLEncoder.encode(chipId, StandardCharsets.UTF_8)
                + "/tags/" + URLEncoder.encode(key, StandardCharsets.UTF_8));
    }

    /**
     * Extracts the list of table names referenced in a SQL query.
     *
     * @param query The SQL query to analyze
     * @return List of table names
     * @throws IOException if the API call fails
     */
    public List<String> extractTables(String query) throws IOException {
        ExtractTablesRequest request = new ExtractTablesRequest(query, null);
        ExtractTablesResponse response = post("/lathe/query/tables", request, ExtractTablesResponse.class);
        if (response.getError() != null) {
            throw new IOException("Failed to extract tables: " + response.getError());
        }
        return response.getTables();
    }

    /**
     * Extracts the list of table names referenced in a SQL query.
     * Optionally transforms the query from MySQL/MariaDB syntax to DuckDB.
     *
     * @param query     The SQL query to analyze
     * @param transform When true, also returns the query transformed to DuckDB
     *                  syntax
     * @return The response containing table names and optionally the transformed
     *         query
     * @throws IOException if the API call fails
     */
    public ExtractTablesResponse extractTablesWithTransform(String query, boolean transform)
            throws IOException {
        ExtractTablesRequest request = new ExtractTablesRequest(query, transform);
        ExtractTablesResponse response = post("/lathe/query/tables", request, ExtractTablesResponse.class);
        if (response.getError() != null) {
            throw new IOException("Failed to extract tables: " + response.getError());
        }
        return response;
    }

    /**
     * Executes queries against a list of chip IDs
     *
     * @param chipIds List of chip IDs to query
     * @param queries List of SQL queries to execute
     * @return Map of query index to Result
     * @throws IOException if the API call fails
     */
    public Map<Integer, GenerateReportResponse.Result> generateReport(List<String> chipIds,
            List<String> queries) throws IOException {
        return generateReport(chipIds, queries, null, null).getResults();
    }

    /**
     * Executes queries against a list of chip IDs with transform query support
     *
     * @param chipIds                List of chip IDs to query
     * @param queries                List of SQL queries to execute
     * @param transformQuery         If true, transform queries from MariaDB to
     *                               DuckDB syntax
     * @param returnTransformedQuery If true, include the transformed query in
     *                               results
     * @return GenerateReportResult containing results map and timing metadata
     * @throws IOException if the API call fails
     */
    public GenerateReportResult generateReport(List<String> chipIds, List<String> queries,
            Boolean transformQuery, Boolean returnTransformedQuery) throws IOException {
        GenerateReportRequest request = new GenerateReportRequest();
        request.setSourceType(SourceType.CHIP);
        request.setQueryRequest(new GenerateReportRequest.Queries(queries));
        request.setChipIds(chipIds);
        request.setTransformQuery(transformQuery);
        request.setReturnTransformedQuery(returnTransformedQuery);

        GenerateReportResponse response = post("/lathe/report", request, GenerateReportResponse.class);

        Map<Integer, GenerateReportResponse.Result> results = new HashMap<>();
        if (response.getResult() != null) {
            for (Map.Entry<String, GenerateReportResponse.Result> entry : response
                    .getResult().entrySet()) {
                int idx = Integer.parseInt(entry.getKey());
                GenerateReportResponse.Result result = entry.getValue();
                results.put(idx, result);
            }
        }

        return new GenerateReportResult(results, response.getTiming());
    }

    // --- AI Credential methods ---

    /**
     * Creates a new AI credential.
     *
     * @param name         Display name for the credential
     * @param provider     AI provider (e.g. "anthropic", "openai")
     * @param apiKey       API key for the provider
     * @param defaultModel Optional default model to use
     * @return The created credential
     * @throws IOException if the API call fails
     */
    public AiCredential createAiCredential(String name, String provider, String apiKey,
            String defaultModel) throws IOException {
        CreateAiCredentialRequest request = CreateAiCredentialRequest.builder()
                .name(name)
                .provider(provider)
                .apiKey(apiKey)
                .defaultModel(defaultModel)
                .build();
        return post("/lathe/ai/credentials", request, AiCredential.class);
    }

    /**
     * Lists all AI credentials (API keys excluded).
     */
    public List<AiCredential> listAiCredentials() throws IOException {
        return Arrays.asList(get("/lathe/ai/credentials", AiCredential[].class));
    }

    /**
     * Deletes an AI credential.
     */
    public void deleteAiCredential(String credentialId) throws IOException {
        httpDelete("/lathe/ai/credentials/" + URLEncoder.encode(credentialId, StandardCharsets.UTF_8));
    }

    // --- AI Context methods ---

    /**
     * Creates a new AI context for natural language queries.
     *
     * @param name                   Display name for the context
     * @param credentialId           Unused (kept for backwards compatibility)
     * @param chipIds                Chip IDs to include in the context
     * @param columnDescriptions     Column descriptions keyed by table name then column name
     * @param dataRelationshipPrompt Description of how the data tables relate to each other
     * @return The created context
     * @throws IOException if the API call fails
     */
    public AiContext createAiContext(String name, String credentialId, List<String> chipIds,
            Map<String, Map<String, String>> columnDescriptions, String dataRelationshipPrompt) throws IOException {
        CreateAiContextRequest request = CreateAiContextRequest.builder()
                .name(name)
                .chipIds(chipIds)
                .columnDescriptions(columnDescriptions)
                .dataRelationshipPrompt(dataRelationshipPrompt)
                .build();
        return post("/lathe/ai/contexts", request, AiContext.class);
    }

    /**
     * Lists all AI contexts.
     */
    public List<AiContext> listAiContexts() throws IOException {
        return Arrays.asList(get("/lathe/ai/contexts", AiContext[].class));
    }

    /**
     * Gets an AI context by ID.
     */
    public AiContext getAiContext(String contextId) throws IOException {
        return get("/lathe/ai/contexts/" + URLEncoder.encode(contextId, StandardCharsets.UTF_8),
                AiContext.class);
    }

    /**
     * Updates an AI context. Only non-null fields in the request are applied.
     *
     * @param contextId The context ID to update
     * @param updates   The fields to update
     * @return The updated context
     * @throws IOException if the API call fails
     */
    public AiContext updateAiContext(String contextId, UpdateAiContextRequest updates)
            throws IOException {
        return put("/lathe/ai/contexts/" + URLEncoder.encode(contextId, StandardCharsets.UTF_8), updates,
                AiContext.class);
    }

    /**
     * Deletes an AI context.
     */
    public void deleteAiContext(String contextId) throws IOException {
        httpDelete("/lathe/ai/contexts/" + URLEncoder.encode(contextId, StandardCharsets.UTF_8));
    }

    // --- AI Query methods ---

    /**
     * Executes a natural language query against an AI context.
     *
     * @param contextId    The AI context to query
     * @param credentialId The AI credential to use
     * @param question     The natural language question
     * @return The query response with data, SQL, and optional visualization
     * @throws IOException if the API call fails
     */
    public AiQueryResponse aiQuery(String contextId, String credentialId, String question)
            throws IOException {
        return aiQuery(contextId, credentialId, question, null, null);
    }

    /**
     * Executes a natural language query with conversation history and model override.
     *
     * @param contextId           The AI context to query
     * @param credentialId        The AI credential to use
     * @param question            The natural language question
     * @param conversationHistory Optional prior conversation turns for context
     * @param model               Optional model override
     * @return The query response with data, SQL, and optional visualization
     * @throws IOException if the API call fails
     */
    public AiQueryResponse aiQuery(String contextId, String credentialId, String question,
            List<ConversationTurn> conversationHistory, String model) throws IOException {
        AiQueryRequest request = AiQueryRequest.builder()
                .contextId(contextId)
                .credentialId(credentialId)
                .userQuestion(question)
                .conversationHistory(conversationHistory)
                .model(model)
                .build();
        return post("/lathe/ai/query", request, AiQueryResponse.class);
    }

    // --- HTTP helpers ---

    @SuppressWarnings("unchecked")
    private <T> T get(String path, Class<T> responseType) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(baseUrl + path)
                .get()
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("GET " + path + " failed: " + response.code());
            }
            return objectMapper.readValue(response.body().string(), responseType);
        }
    }

    private <T> T post(String path, Object body, Class<T> responseType) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                .build();

        logger.debug("POST {}: {}", path, objectMapper.writeValueAsString(body));

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("POST " + path + " failed: " + response.code() + " "
                        + response.body().string());
            }
            return objectMapper.readValue(response.body().string(), responseType);
        }
    }

    private <T> T put(String path, Object body, Class<T> responseType) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(baseUrl + path)
                .put(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("PUT " + path + " failed: " + response.code() + " "
                        + response.body().string());
            }
            return objectMapper.readValue(response.body().string(), responseType);
        }
    }

    private void httpDelete(String path) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(baseUrl + path)
                .delete()
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("DELETE " + path + " failed: " + response.code());
            }
        }
    }
}
