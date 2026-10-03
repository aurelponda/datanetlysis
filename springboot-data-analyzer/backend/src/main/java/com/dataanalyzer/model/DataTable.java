package com.dataanalyzer.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DataTable(List<String> columns, List<Map<String, Object>> rows) {
    public DataTable copy() {
        List<Map<String, Object>> copiedRows = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) copiedRows.add(new LinkedHashMap<>(row));
        return new DataTable(new ArrayList<>(columns), copiedRows);
    }
}
