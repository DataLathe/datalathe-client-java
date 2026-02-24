package com.datalathe.client;

import com.datalathe.client.command.impl.GenerateReportCommand;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class GenerateReportResult {
    private Map<Integer, GenerateReportCommand.Response.Result> results;
    private GenerateReportCommand.Response.ReportTiming timing;
}
