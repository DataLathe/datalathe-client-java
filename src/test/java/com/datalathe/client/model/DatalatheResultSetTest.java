package com.datalathe.client.model;

import com.datalathe.client.command.impl.GenerateReportCommand.Response.Result;
import com.datalathe.client.results.DatalatheResultSet;
import com.datalathe.client.results.Schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatalatheResultSetTest {
    private DatalatheResultSet resultSet;

    @BeforeEach
    void setUp() {
        // Create test data
        List<List<String>> data = new ArrayList<>();
        List<Schema> schema = new ArrayList<>();

        // Add schema
        Schema idSchema = new Schema();
        idSchema.setName("id");
        idSchema.setDataType("Int32");
        schema.add(idSchema);

        Schema nameSchema = new Schema();
        nameSchema.setName("name");
        nameSchema.setDataType("Utf8");
        schema.add(nameSchema);

        Schema ageSchema = new Schema();
        ageSchema.setName("age");
        ageSchema.setDataType("Int32");
        schema.add(ageSchema);

        Schema activeSchema = new Schema();
        activeSchema.setName("active");
        activeSchema.setDataType("Boolean");
        schema.add(activeSchema);

        Schema scoreSchema = new Schema();
        scoreSchema.setName("score");
        scoreSchema.setDataType("Float64");
        schema.add(scoreSchema);

        // Add rows
        List<String> row1 = new ArrayList<>();
        row1.add("1");
        row1.add("John");
        row1.add("30");
        row1.add("true");
        row1.add("95.5");

        List<String> row2 = new ArrayList<>();
        row2.add("2");
        row2.add("Jane");
        row2.add("25");
        row2.add("false");
        row2.add("88.0");

        List<String> row3 = new ArrayList<>();
        row3.add("3");
        row3.add(null);
        row3.add("35");
        row3.add("true");
        row3.add(null);

        data.add(row1);
        data.add(row2);
        data.add(row3);

        // Create GenericResult
        Result result = new Result();
        result.setResult(data);
        result.setSchema(schema);

        resultSet = new DatalatheResultSet(result);
    }

    @Test
    void testNext() throws SQLException {
        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getInt(1));
        assertEquals("John", resultSet.getString(2));

        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getInt(1));
        assertEquals("Jane", resultSet.getString(2));

        assertTrue(resultSet.next());
        assertEquals(3, resultSet.getInt(1));
        assertNull(resultSet.getString(2));

        assertFalse(resultSet.next());
    }

    @Test
    void testGetString() throws SQLException {
        resultSet.next();
        assertEquals("John", resultSet.getString(2));
        assertEquals("John", resultSet.getString("name"));

        resultSet.next();
        assertEquals("Jane", resultSet.getString(2));
        assertEquals("Jane", resultSet.getString("name"));

        resultSet.next();
        assertNull(resultSet.getString(2));
        assertNull(resultSet.getString("name"));
    }

    @Test
    void testGetInt() throws SQLException {
        resultSet.next();
        assertEquals(1, resultSet.getInt(1));
        assertEquals(1, resultSet.getInt("id"));
        assertEquals(30, resultSet.getInt(3));
        assertEquals(30, resultSet.getInt("age"));
    }

    @Test
    void testGetBoolean() throws SQLException {
        resultSet.next();
        assertTrue(resultSet.getBoolean(4));
        assertTrue(resultSet.getBoolean("active"));

        resultSet.next();
        assertFalse(resultSet.getBoolean(4));
        assertFalse(resultSet.getBoolean("active"));
    }

    @Test
    void testGetDouble() throws SQLException {
        resultSet.next();
        assertEquals(95.5, resultSet.getDouble(5), 0.001);
        assertEquals(95.5, resultSet.getDouble("score"), 0.001);
        assertFalse(resultSet.wasNull());

        resultSet.next();
        assertEquals(88.0, resultSet.getDouble(5), 0.001);
        assertEquals(88.0, resultSet.getDouble("score"), 0.001);
        assertFalse(resultSet.wasNull());

        resultSet.next();
        assertEquals(0.0, resultSet.getDouble(5), 0.001);
        assertEquals(0.0, resultSet.getDouble("score"), 0.001);
        assertTrue(resultSet.wasNull());
    }

    @Test
    void testWasNull() throws SQLException {
        resultSet.next();
        assertFalse(resultSet.wasNull());
        resultSet.getString(2);
        assertFalse(resultSet.wasNull());

        resultSet.next();
        assertFalse(resultSet.wasNull());
        resultSet.getString(2);
        assertFalse(resultSet.wasNull());

        resultSet.next();
        assertFalse(resultSet.wasNull());
        resultSet.getString(2);
        assertTrue(resultSet.wasNull());
    }

    @Test
    void testGetMetaData() throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();

        assertEquals(5, metaData.getColumnCount());
        assertEquals("id", metaData.getColumnName(1));
        assertEquals("name", metaData.getColumnName(2));
        assertEquals("age", metaData.getColumnName(3));
        assertEquals("active", metaData.getColumnName(4));
        assertEquals("score", metaData.getColumnName(5));

        assertTrue(metaData.isCaseSensitive(1));
        assertTrue(metaData.isSearchable(1));
        assertFalse(metaData.isCurrency(1));
        assertTrue(metaData.isSigned(1));
        assertEquals(Types.INTEGER, metaData.getColumnType(1));
        assertEquals("Int32", metaData.getColumnTypeName(1));
    }

    @Test
    void testNavigation() throws SQLException {
        assertTrue(resultSet.isBeforeFirst());

        assertTrue(resultSet.next());
        assertTrue(resultSet.isFirst());

        assertTrue(resultSet.next());
        assertFalse(resultSet.isFirst());
        assertFalse(resultSet.isLast());

        assertTrue(resultSet.next());
        assertTrue(resultSet.isLast());

        assertFalse(resultSet.next());
        assertTrue(resultSet.isAfterLast());

        resultSet.beforeFirst();
        assertTrue(resultSet.isBeforeFirst());

        assertTrue(resultSet.first());
        assertEquals(1, resultSet.getInt(1));

        assertTrue(resultSet.last());
        assertEquals(3, resultSet.getInt(1));

        assertTrue(resultSet.absolute(2));
        assertEquals(2, resultSet.getInt(1));

        assertTrue(resultSet.relative(-1));
        assertEquals(1, resultSet.getInt(1));

        assertTrue(resultSet.relative(1));
        assertEquals(2, resultSet.getInt(1));

        assertTrue(resultSet.previous());
        assertEquals(1, resultSet.getInt(1));
    }

    @Test
    void testGetRow() throws SQLException {
        assertEquals(0, resultSet.getRow());

        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getRow());

        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getRow());

        assertTrue(resultSet.next());
        assertEquals(3, resultSet.getRow());
    }

    @Test
    void testFindColumn() throws SQLException {
        assertEquals(1, resultSet.findColumn("id"));
        assertEquals(2, resultSet.findColumn("name"));
        assertEquals(3, resultSet.findColumn("age"));
        assertEquals(4, resultSet.findColumn("active"));
        assertEquals(5, resultSet.findColumn("score"));

        assertThrows(SQLException.class, () -> resultSet.findColumn("nonexistent"));
    }

    @Test
    void testGetObject() throws SQLException {
        resultSet.next();

        assertEquals(1, resultSet.getObject(1));
        assertEquals("John", resultSet.getObject(2));
        assertEquals(30, resultSet.getObject(3));
        assertEquals(true, resultSet.getObject(4));
        assertEquals(95.5, resultSet.getObject(5));

        assertEquals(1, resultSet.getObject("id"));
        assertEquals("John", resultSet.getObject("name"));
        assertEquals(30, resultSet.getObject("age"));
        assertEquals(true, resultSet.getObject("active"));
        assertEquals(95.5, resultSet.getObject("score"));
    }

    @Test
    void testGetObjectWithType() throws SQLException {
        resultSet.next();

        assertEquals(Integer.valueOf(1), resultSet.getObject(1, Integer.class));
        assertEquals("John", resultSet.getObject(2, String.class));
        assertEquals(Integer.valueOf(30), resultSet.getObject(3, Integer.class));
        assertEquals(Boolean.TRUE, resultSet.getObject(4, Boolean.class));
        assertEquals(Double.valueOf(95.5), resultSet.getObject(5, Double.class));

        assertThrows(SQLException.class, () -> resultSet.getObject(2, Long.class));
    }

    @Test
    void testEmptyResultSet() throws SQLException {
        Result emptyResult = new Result();
        emptyResult.setResult(new ArrayList<>());
        emptyResult.setSchema(new ArrayList<>());

        DatalatheResultSet emptyResultSet = new DatalatheResultSet(emptyResult);

        assertFalse(emptyResultSet.next());
        assertFalse(emptyResultSet.isBeforeFirst());
        assertFalse(emptyResultSet.isFirst());
        assertFalse(emptyResultSet.isLast());
        assertFalse(emptyResultSet.isAfterLast());

        ResultSetMetaData metaData = emptyResultSet.getMetaData();
        assertEquals(0, metaData.getColumnCount());

        emptyResultSet.close();
    }
}