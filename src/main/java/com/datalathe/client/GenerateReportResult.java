package com.datalathe.client;

import com.datalathe.client.types.GenerateReportResponse;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class GenerateReportResult {
    private Map<Integer, GenerateReportResponse.Result> results;
    private GenerateReportResponse.ReportTiming timing;
}
