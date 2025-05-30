package com.datalathe.client.examples;

import com.datalathe.client.DatalatheClient;

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

    /**
     * Example of staging data from multiple tables and then querying them
     */
    public void runExample() {
        try {
            // Stage data from multiple tables
            List<String> stageQueries = Arrays.asList(
                    "SELECT * FROM users WHERE created_at > '2024-01-01'",
                    "SELECT * FROM orders WHERE status = 'completed'");

            System.out.println("Staging data...");
            List<String> chipIds = client.stageData("my_database", stageQueries, "object028");
            System.out.println("Staged data with chip IDs: " + chipIds);

            // Example queries to run against the staged data
            List<String> analysisQueries = Arrays.asList(
                    // Query 1: Basic aggregation
                    "SELECT " +
                            "  COUNT(*) as total_users, " +
                            "  AVG(age) as avg_age, " +
                            "  MIN(created_at) as first_user, " +
                            "  MAX(created_at) as last_user " +
                            "FROM users",

                    // Query 2: Join between users and orders
                    "SELECT " +
                            "  u.user_id, " +
                            "  u.email, " +
                            "  COUNT(o.order_id) as order_count, " +
                            "  SUM(o.total_amount) as total_spent " +
                            "FROM users u " +
                            "LEFT JOIN orders o ON u.user_id = o.user_id " +
                            "GROUP BY u.user_id, u.email",

                    // Query 3: Complex analysis with window functions
                    "SELECT " +
                            "  user_id, " +
                            "  order_date, " +
                            "  total_amount, " +
                            "  AVG(total_amount) OVER (PARTITION BY user_id ORDER BY order_date ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) as moving_avg "
                            +
                            "FROM orders " +
                            "WHERE status = 'completed'");

            System.out.println("\nExecuting analysis queries...");
            Map<Integer, ResultSet> results = client.query(chipIds, analysisQueries);

            // Process each result set
            for (Map.Entry<Integer, ResultSet> entry : results.entrySet()) {
                int queryIndex = entry.getKey();
                ResultSet rs = entry.getValue();

                System.out.println("\nResults for Query " + (queryIndex + 1) + ":");
                System.out.println("----------------------------------------");

                // Print column headers
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    System.out.printf("%-20s", metaData.getColumnName(i));
                }
                System.out.println();

                // Print data rows
                while (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        String value = rs.getString(i);
                        System.out.printf("%-20s", value != null ? value : "NULL");
                    }
                    System.out.println();
                }

                // Close the result set
                rs.close();
            }

        } catch (IOException | SQLException e) {
            System.err.println("Error running example: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Example of handling different data types in the ResultSet
     */
    public void demonstrateDataTypes() {
        try {
            // Stage data with various data types
            List<String> chipIds = client.stageData("my_database",
                    Collections.singletonList("SELECT * FROM mixed_data_types"), "mixed_data_types");

            // Query to demonstrate different data types
            List<String> queries = Collections.singletonList(
                    "SELECT " +
                            "  id, " +
                            "  name, " +
                            "  age, " +
                            "  salary, " +
                            "  is_active, " +
                            "  created_at, " +
                            "  score " +
                            "FROM mixed_data_types");

            Map<Integer, ResultSet> results = client.query(chipIds, queries);
            ResultSet rs = results.get(0);

            System.out.println("\nDemonstrating different data types:");
            System.out.println("----------------------------------------");

            while (rs.next()) {
                // String
                String name = rs.getString("name");

                // Integer
                int age = rs.getInt("age");

                // Double
                double salary = rs.getDouble("salary");

                // Boolean
                boolean isActive = rs.getBoolean("is_active");

                // Date (as string in this example)
                String createdAt = rs.getString("created_at");

                // Float
                float score = rs.getFloat("score");

                System.out.printf("Name: %s, Age: %d, Salary: %.2f, Active: %b, Created: %s, Score: %.1f%n",
                        name, age, salary, isActive, createdAt, score);
            }

            rs.close();

        } catch (IOException | SQLException e) {
            System.err.println("Error demonstrating data types: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Replace with your Datalathe server URL
        String baseUrl = "http://localhost:8080";
        DatalatheClientExample example = new DatalatheClientExample(baseUrl);

        // Run the main example
        example.runExample();

        // Demonstrate data type handling
        example.demonstrateDataTypes();
    }
}