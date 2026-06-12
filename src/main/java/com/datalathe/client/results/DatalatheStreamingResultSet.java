package com.datalathe.client.results;

import com.datalathe.client.DatalatheQueryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Response;
import okio.BufferedSource;

import java.io.IOException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A forward-only ({@link java.sql.ResultSet#TYPE_FORWARD_ONLY}) result set that
 * lazily consumes an NDJSON stream from the engine's streaming report endpoint
 * ({@code POST /lathe/report} with {@code "stream": true}).
 *
 * <p>Each call to {@link #next()} pulls and parses one or more frames from the
 * underlying OkHttp {@link BufferedSource} until a row is available, the stream
 * ends, or a terminal {@code error} frame arrives. Backward navigation
 * ({@code previous}, {@code first}, {@code last}, {@code absolute},
 * {@code beforeFirst}, {@code relative} to a prior row) throws
 * {@link SQLFeatureNotSupportedException}.</p>
 *
 * <p>Closing the result set closes the HTTP response body, which aborts the
 * server-side stream. The instance is {@link AutoCloseable}, so it works in a
 * try-with-resources block.</p>
 */
public class DatalatheStreamingResultSet extends AbstractResultSet {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Response response;
    private final BufferedSource source;

    private List<Schema> schema;
    private String transformedQuery;

    private List<List<String>> buffer = new ArrayList<>();
    private int bufferIndex = 0;
    private List<String> currentRow;

    private boolean schemaSeen = false;
    private boolean terminated = false;
    private boolean closed = false;
    private boolean wasNull = false;

    private long rowCount = -1;
    private long emittedRows = 0;

    public DatalatheStreamingResultSet(Response response) throws SQLException {
        this.response = response;
        if (response.body() == null) {
            throw new SQLException("Streaming report response had no body");
        }
        this.source = response.body().source();
        readUntilSchema();
    }

    /**
     * The total row count reported by the terminal {@code end} frame. Returns
     * {@code -1} until the stream has been fully consumed.
     */
    public long getRowCount() {
        return rowCount;
    }

    /**
     * The query as transformed for the engine, when {@code returnTransformedQuery}
     * was requested; otherwise {@code null}.
     */
    public String getTransformedQuery() {
        return transformedQuery;
    }

    private void readUntilSchema() throws SQLException {
        while (!schemaSeen) {
            JsonNode frame = readFrame();
            if (frame == null) {
                throw new SQLException(
                        "Streaming report ended before a schema frame was received");
            }
            handleFrame(frame);
        }
    }

    private JsonNode readFrame() throws SQLException {
        try {
            String line = source.readUtf8Line();
            while (line != null && line.isEmpty()) {
                line = source.readUtf8Line();
            }
            if (line == null) {
                return null;
            }
            return MAPPER.readTree(line);
        } catch (IOException e) {
            throw new SQLException("Failed to read streaming report frame", e);
        }
    }

    private void handleFrame(JsonNode frame) throws SQLException {
        JsonNode typeNode = frame.get("type");
        String type = typeNode != null ? typeNode.asText() : null;
        if (type == null) {
            throw new SQLException("Streaming report frame missing \"type\": " + frame);
        }
        switch (type) {
            case "schema":
                handleSchema(frame);
                break;
            case "rows":
                handleRows(frame);
                break;
            case "end":
                handleEnd(frame);
                break;
            case "error":
                handleError(frame);
                break;
            default:
                throw new SQLException("Unknown streaming report frame type: " + type);
        }
    }

    private void handleSchema(JsonNode frame) throws SQLException {
        if (schemaSeen) {
            throw new SQLException("Streaming report sent a second schema frame");
        }
        List<Schema> parsed = new ArrayList<>();
        JsonNode schemaNode = frame.get("schema");
        if (schemaNode != null && schemaNode.isArray()) {
            for (JsonNode col : schemaNode) {
                JsonNode nameNode = col.get("name");
                JsonNode typeNode = col.get("data_type");
                parsed.add(new Schema(
                        nameNode != null ? nameNode.asText() : null,
                        typeNode != null ? typeNode.asText() : null));
            }
        }
        this.schema = Collections.unmodifiableList(parsed);
        JsonNode tq = frame.get("transformed_query");
        if (tq != null && !tq.isNull()) {
            this.transformedQuery = tq.asText();
        }
        this.schemaSeen = true;
    }

    private void handleRows(JsonNode frame) throws SQLException {
        List<List<String>> batch = new ArrayList<>();
        JsonNode rowsNode = frame.get("rows");
        if (rowsNode != null && rowsNode.isArray()) {
            for (JsonNode rowNode : rowsNode) {
                List<String> row = new ArrayList<>();
                for (JsonNode cell : rowNode) {
                    row.add(cell.isNull() ? null : cell.asText());
                }
                batch.add(row);
            }
        }
        this.buffer = batch;
        this.bufferIndex = 0;
    }

    private void handleEnd(JsonNode frame) {
        JsonNode rc = frame.get("row_count");
        if (rc != null && rc.isNumber()) {
            this.rowCount = rc.asLong();
        }
        this.terminated = true;
    }

    private void handleError(JsonNode frame) throws SQLException {
        this.terminated = true;
        JsonNode errNode = frame.get("error");
        String message = errNode != null ? errNode.asText() : "unknown streaming error";
        closeQuietly();
        throw new SQLException(new DatalatheQueryException(
                Collections.singletonMap(0, message)));
    }

    @Override
    public boolean next() throws SQLException {
        if (closed) {
            throw new SQLException("Result set is closed");
        }
        while (true) {
            if (bufferIndex < buffer.size()) {
                currentRow = buffer.get(bufferIndex++);
                emittedRows++;
                return true;
            }
            if (terminated) {
                currentRow = null;
                return false;
            }
            JsonNode frame = readFrame();
            if (frame == null) {
                terminated = true;
                currentRow = null;
                closeQuietly();
                throw new SQLException(
                        "Streaming report ended without a terminal frame "
                                + "(transport failure after " + emittedRows + " rows)");
            }
            handleFrame(frame);
        }
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            response.close();
        }
    }

    private void closeQuietly() {
        if (!closed) {
            closed = true;
            try {
                response.close();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public int getType() throws SQLException {
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        return CONCUR_READ_ONLY;
    }

    @Override
    public boolean wasNull() throws SQLException {
        return wasNull;
    }

    // --- Accessors ---

    private String getValue(int columnIndex) throws SQLException {
        if (currentRow == null) {
            throw new SQLException("No current row");
        }
        if (columnIndex < 1 || columnIndex > schema.size()) {
            throw new SQLException("Invalid column index: " + columnIndex);
        }
        String value = currentRow.get(columnIndex - 1);
        if (value != null && value.isEmpty()) {
            return null;
        }
        return value;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        String value = getValue(columnIndex);
        wasNull = value == null;
        return value;
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        String value = getValue(columnIndex);
        wasNull = value == null;
        return value != null && Boolean.parseBoolean(value);
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        String value = getValue(columnIndex);
        wasNull = value == null;
        return value == null ? 0 : Byte.parseByte(value);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        String value = getValue(columnIndex);
        wasNull = value == null;
        return value == null ? 0 : Short.parseShort(value);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        String value = getValue(columnIndex);
        wasNull = value == null;
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return (int) Double.parseDouble(value);
        }
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        String value = getValue(columnIndex);
        wasNull = value == null;
        return value == null ? 0L : Long.parseLong(value);
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        String value = getValue(columnIndex);
        wasNull = value == null;
        return value == null ? 0.0f : Float.parseFloat(value);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        String value = getValue(columnIndex);
        wasNull = value == null;
        return value == null ? 0.0 : Double.parseDouble(value);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        String value = getValue(columnIndex);
        wasNull = value == null;
        if (value == null) {
            return null;
        }
        String dataType = schema.get(columnIndex - 1).getDataType();
        try {
            switch (dataType) {
                case "Int32":
                    return Integer.parseInt(value);
                case "Int64":
                    return Long.parseLong(value);
                case "Float32":
                    return Float.parseFloat(value);
                case "Float64":
                    return Double.parseDouble(value);
                case "Boolean":
                    return Boolean.parseBoolean(value);
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert value to requested type: " + dataType, e);
        }
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        Object value = getObject(columnIndex);
        if (value == null) {
            return null;
        }
        try {
            return type.cast(value);
        } catch (ClassCastException e) {
            throw new SQLException("Cannot convert value to requested type: " + type.getName(), e);
        }
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).getName().equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("Column not found: " + columnLabel);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return new ResultSetMetaData() {
            @Override
            public int getColumnCount() {
                return schema.size();
            }

            @Override
            public boolean isAutoIncrement(int column) {
                return false;
            }

            @Override
            public boolean isCaseSensitive(int column) {
                return true;
            }

            @Override
            public boolean isSearchable(int column) {
                return true;
            }

            @Override
            public boolean isCurrency(int column) {
                return false;
            }

            @Override
            public int isNullable(int column) {
                return columnNullable;
            }

            @Override
            public boolean isSigned(int column) {
                return true;
            }

            @Override
            public int getColumnDisplaySize(int column) {
                return 0;
            }

            @Override
            public String getColumnLabel(int column) throws SQLException {
                return getColumnName(column);
            }

            @Override
            public String getColumnName(int column) {
                return schema.get(column - 1).getName();
            }

            @Override
            public String getSchemaName(int column) {
                return "";
            }

            @Override
            public int getPrecision(int column) {
                return 0;
            }

            @Override
            public int getScale(int column) {
                return 0;
            }

            @Override
            public String getTableName(int column) {
                return "";
            }

            @Override
            public String getCatalogName(int column) {
                return "";
            }

            @Override
            public int getColumnType(int column) {
                String dataType = schema.get(column - 1).getDataType();
                switch (dataType) {
                    case "Int32":
                    case "Int64":
                        return Types.INTEGER;
                    case "Float32":
                    case "Float64":
                        return Types.DOUBLE;
                    case "Boolean":
                        return Types.BOOLEAN;
                    default:
                        return Types.VARCHAR;
                }
            }

            @Override
            public String getColumnTypeName(int column) {
                return schema.get(column - 1).getDataType();
            }

            @Override
            public boolean isReadOnly(int column) {
                return true;
            }

            @Override
            public boolean isWritable(int column) {
                return false;
            }

            @Override
            public boolean isDefinitelyWritable(int column) {
                return false;
            }

            @Override
            public String getColumnClassName(int column) {
                String dataType = schema.get(column - 1).getDataType();
                switch (dataType) {
                    case "Int32":
                        return Integer.class.getName();
                    case "Int64":
                        return Long.class.getName();
                    case "Float32":
                        return Float.class.getName();
                    case "Float64":
                        return Double.class.getName();
                    case "Boolean":
                        return Boolean.class.getName();
                    default:
                        return String.class.getName();
                }
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    // --- Forward-only: backward navigation is unsupported ---

    @Override
    public boolean previous() throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Streaming result sets are forward-only");
    }

    @Override
    public boolean first() throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Streaming result sets are forward-only");
    }

    @Override
    public boolean last() throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Streaming result sets are forward-only");
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Streaming result sets are forward-only");
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Streaming result sets are forward-only");
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Streaming result sets are forward-only");
    }

    @Override
    public void afterLast() throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Streaming result sets are forward-only");
    }
}
