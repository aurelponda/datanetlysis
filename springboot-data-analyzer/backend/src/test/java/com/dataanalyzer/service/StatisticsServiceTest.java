package com.dataanalyzer.service;

import com.dataanalyzer.model.DataTable;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StatisticsServiceTest {
    private final StatisticsService service = new StatisticsService();

    @Test
    void calculatesCenterSpreadAndQuartiles() {
        DataTable table = table(List.of(1d, 2d, 2d, 4d));
        Map<String, Object> result = service.describe(table, "Score");
        assertEquals(4, result.get("count"));
        assertEquals(2.25, (Double) result.get("mean"), 1e-10);
        assertEquals(2d, (Double) result.get("median"), 1e-10);
        assertEquals(2d, (Double) result.get("mode"), 1e-10);
        assertEquals(1.1875, (Double) result.get("variance"), 1e-10);
        assertEquals(1.75, (Double) result.get("q1"), 1e-10);
        assertEquals(2.5, (Double) result.get("q3"), 1e-10);
    }

    @Test
    void handlesConstantSeriesWithoutNaN() {
        Map<String, Object> result = service.describe(table(List.of(5d, 5d, 5d)), "Score");
        assertEquals(0d, (Double) result.get("standardDeviation"), 1e-10);
        assertEquals(0d, (Double) result.get("skewness"), 1e-10);
        assertEquals(0d, (Double) result.get("coefficientOfVariation"), 1e-10);
        assertNull(service.describe(table(List.of(0d, 0d)), "Score").get("coefficientOfVariation"));
    }

    private DataTable table(List<Double> values) {
        return new DataTable(List.of("Score"), values.stream().map(value -> {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("Score", value); return row;
        }).toList());
    }
}
