package com.datalathe.client;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Thrown by {@code generateReport} when one or more queries fail at execution
 * time. The engine returns HTTP 200 with the failure in each entry's
 * {@code error} field; this surfaces it instead of letting a failed query
 * look like an empty result.
 *
 * <p>Pass {@code raiseOnQueryError = false} to {@code generateReport} to
 * suppress this and inspect {@code GenerateReportResponse.Result.getError()}
 * on the returned results instead.
 */
public class DatalatheQueryException extends IOException {
    private static final long serialVersionUID = 1L;

    private final Map<Integer, String> errors;

    public DatalatheQueryException(Map<Integer, String> errors) {
        super(buildMessage(errors));
        this.errors = errors;
    }

    /** Failed query index mapped to its execution error message. */
    public Map<Integer, String> getErrors() {
        return errors;
    }

    private static String buildMessage(Map<Integer, String> errors) {
        StringBuilder sb = new StringBuilder("Query execution failed (");
        boolean first = true;
        for (Map.Entry<Integer, String> entry : new TreeMap<>(errors).entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            sb.append("query ").append(entry.getKey()).append(": ").append(entry.getValue());
            first = false;
        }
        return sb.append(")").toString();
    }
}
