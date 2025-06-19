package com.datalathe;

import java.sql.ResultSet;
import com.datalathe.client.command.impl.GenerateReportCommand;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Util {
    private static final Logger logger = LogManager.getLogger(Util.class);

    public static void printReportResults(Map<Integer, GenerateReportCommand.Response.Result> results)
            throws SQLException {
        for (GenerateReportCommand.Response.Result result : results.values()) {
            logger.info("Query {} results:", result.getIdx());
            if (result.getError() != null) {
                logger.info("Error: {}", result.getError());
                continue;
            }
            ResultSet rs = result.getResultSet();
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // Print column headers
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    header.append(" | ");
                }
                header.append(metaData.getColumnName(i));
            }
            logger.info(header.toString());

            // Print data rows
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append(" | ");
                    }
                    String value = rs.getString(i);
                    row.append(value != null ? value : "NULL");
                }
                logger.info(row.toString());
            }
            logger.info("");
        }
    }
}