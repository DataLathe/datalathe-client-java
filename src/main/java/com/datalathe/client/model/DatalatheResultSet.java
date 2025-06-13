package com.datalathe.client.model;

import com.datalathe.client.command.impl.GenerateReportCommand.Response.Result;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.List;

public class DatalatheResultSet extends AbstractResultSet {
    private final List<List<String>> data;
    private final List<Schema> schema;
    private int currentRow = -1;
    private boolean wasNull = false;

    public DatalatheResultSet(Result result) {
        this.data = result.getResult();
        this.schema = result.getSchema();
    }

    @Override
    public boolean next() throws SQLException {
        return ++currentRow < data.size();
    }

    @Override
    public void close() throws SQLException {
        // No resources to close
    }

    @Override
    public boolean wasNull() throws SQLException {
        return wasNull;
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
        return value == null ? 0 : Integer.parseInt(value);
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
    public ResultSetMetaData getMetaData() throws SQLException {
        return new ResultSetMetaData() {
            @Override
            public int getColumnCount() throws SQLException {
                return schema.size();
            }

            @Override
            public boolean isAutoIncrement(int column) throws SQLException {
                return false;
            }

            @Override
            public boolean isCaseSensitive(int column) throws SQLException {
                return true;
            }

            @Override
            public boolean isSearchable(int column) throws SQLException {
                return true;
            }

            @Override
            public boolean isCurrency(int column) throws SQLException {
                return false;
            }

            @Override
            public int isNullable(int column) throws SQLException {
                return columnNullable;
            }

            @Override
            public boolean isSigned(int column) throws SQLException {
                return true;
            }

            @Override
            public int getColumnDisplaySize(int column) throws SQLException {
                return 0;
            }

            @Override
            public String getColumnLabel(int column) throws SQLException {
                return getColumnName(column);
            }

            @Override
            public String getColumnName(int column) throws SQLException {
                return schema.get(column - 1).getName();
            }

            @Override
            public String getSchemaName(int column) throws SQLException {
                return "";
            }

            @Override
            public int getPrecision(int column) throws SQLException {
                return 0;
            }

            @Override
            public int getScale(int column) throws SQLException {
                return 0;
            }

            @Override
            public String getTableName(int column) throws SQLException {
                return "";
            }

            @Override
            public String getCatalogName(int column) throws SQLException {
                return "";
            }

            @Override
            public int getColumnType(int column) throws SQLException {
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
            public String getColumnTypeName(int column) throws SQLException {
                return schema.get(column - 1).getDataType();
            }

            @Override
            public boolean isReadOnly(int column) throws SQLException {
                return true;
            }

            @Override
            public boolean isWritable(int column) throws SQLException {
                return false;
            }

            @Override
            public boolean isDefinitelyWritable(int column) throws SQLException {
                return false;
            }

            @Override
            public String getColumnClassName(int column) throws SQLException {
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
            public boolean isWrapperFor(Class<?> iface) throws SQLException {
                return false;
            }
        };
    }

    private String getValue(int columnIndex) throws SQLException {
        if (currentRow < 0 || currentRow >= data.size()) {
            throw new SQLException("No current row");
        }
        if (columnIndex < 1 || columnIndex > schema.size()) {
            throw new SQLException("Invalid column index: " + columnIndex);
        }
        return data.get(currentRow).get(columnIndex - 1);
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
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
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
    public int findColumn(String columnLabel) throws SQLException {
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).getName().equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("Column not found: " + columnLabel);
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        return currentRow == -1;
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        return data.size() > 0 && currentRow >= data.size();
    }

    @Override
    public boolean isFirst() throws SQLException {
        return data.size() > 0 && currentRow == 0;
    }

    @Override
    public boolean isLast() throws SQLException {
        return data.size() > 0 && currentRow == data.size() - 1;
    }

    @Override
    public void beforeFirst() throws SQLException {
        currentRow = -1;
    }

    @Override
    public void afterLast() throws SQLException {
        currentRow = data.size();
    }

    @Override
    public boolean first() throws SQLException {
        if (data.isEmpty()) {
            return false;
        }
        currentRow = 0;
        return true;
    }

    @Override
    public boolean last() throws SQLException {
        if (data.isEmpty()) {
            return false;
        }
        currentRow = data.size() - 1;
        return true;
    }

    @Override
    public int getRow() throws SQLException {
        return currentRow + 1;
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        if (row < 0) {
            row = data.size() + row + 1;
        }
        if (row < 1 || row > data.size()) {
            currentRow = data.size();
            return false;
        }
        currentRow = row - 1;
        return true;
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        return absolute(currentRow + 1 + rows);
    }

    @Override
    public boolean previous() throws SQLException {
        if (currentRow <= 0) {
            return false;
        }
        currentRow--;
        return true;
    }
}