package com.datalathe.client.examples;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.command.impl.CreateChipCommand;
import com.datalathe.client.command.impl.GenerateReportCommand;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

public class DatalatheClientExample {
    private final DatalatheClient client;

    public DatalatheClientExample(String baseUrl) {
        this.client = new DatalatheClient(baseUrl);
    }

    public void runExample() throws IOException, SQLException {
        // Stage data from multiple tables
        List<CreateChipCommand.Request.Source> stageQueries = Arrays.asList(
                new CreateChipCommand.Request.Source("my_database", "users",
                        "SELECT * FROM users WHERE created_at > '2024-01-01'"),
                new CreateChipCommand.Request.Source("my_database", "orders",
                        "SELECT * FROM orders WHERE status = 'completed'"));

        List<String> chipIds = client.createChips(stageQueries);
        System.out.println("Staged data with chip IDs: " + chipIds);

        // Example queries
        List<String> analysisQueries = Arrays.asList(
                "SELECT COUNT(*) as total_users, AVG(age) as avg_age FROM users",
                "SELECT u.user_id, COUNT(o.order_id) as order_count FROM users u LEFT JOIN orders o ON u.user_id = o.user_id GROUP BY u.user_id",
                "SELECT user_id, total_amount FROM orders WHERE status = 'completed'");

        Map<Integer, GenerateReportCommand.Response.Result> results = client.generateReport(chipIds,
                analysisQueries);

        // Print results
        for (Map.Entry<Integer, GenerateReportCommand.Response.Result> entry : results
                .entrySet()) {
            ResultSet rs = entry.getValue().getResultSet();
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            System.out.println("\nResults for Query " + (entry.getKey() + 1) + ":");
            System.out.println("----------------------------------------");

            // Headers
            for (int i = 1; i <= columnCount; i++) {
                System.out.printf("%-20s", metaData.getColumnName(i));
            }
            System.out.println();

            // Data
            while (rs.next()) {
                for (int i = 1; i <= columnCount; i++) {
                    String value = rs.getString(i);
                    System.out.printf("%-20s", value != null ? value : "NULL");
                }
                System.out.println();
            }
            rs.close();
        }
    }

    public void demonstrateDataTypes() throws IOException, SQLException {
        String chipId = client.createChip("my_database", "SELECT * FROM mixed_data_types", "mixed_data_types");

        List<String> queries = Collections.singletonList(
                "SELECT id, name, age, salary, is_active, created_at, score FROM mixed_data_types");

        Map<Integer, GenerateReportCommand.Response.Result> results = client
                .generateReport(Collections.singletonList(chipId), queries);
        ResultSet rs = results.get(0).getResultSet();

        System.out.println("\nDemonstrating different data types:");
        System.out.println("----------------------------------------");

        while (rs.next()) {
            System.out.printf("Name: %s, Age: %d, Salary: %.2f, Active: %b, Created: %s, Score: %.1f%n",
                    rs.getString("name"),
                    rs.getInt("age"),
                    rs.getDouble("salary"),
                    rs.getBoolean("is_active"),
                    rs.getString("created_at"),
                    rs.getFloat("score"));
        }
        rs.close();
    }

    public static void main(String[] args) {
        String baseUrl = "http://localhost:8080";
        DatalatheClientExample example = new DatalatheClientExample(baseUrl);

        try {
            example.runExample();
            example.demonstrateDataTypes();
        } catch (IOException | SQLException e) {
            System.err.println("Error running example: " + e.getMessage());
            e.printStackTrace();
        }
    }
}