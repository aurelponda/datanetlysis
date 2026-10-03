package com.dataanalyzer.service;

import com.dataanalyzer.model.DataTable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatisticsService {
    public Map<String, Object> describe(DataTable table, String column) {
        if (!table.columns().contains(column)) throw new IllegalArgumentException("Kolom tidak ditemukan: " + column);
        List<Double> values = table.rows().stream().map(row -> DatasetParser.parseNumber(row.get(column) == null ? null : row.get(column).toString())).filter(v -> v != null).sorted().toList();
        if (values.isEmpty()) return Map.of("column", column, "count", 0, "message", "Kolom tidak memiliki nilai numerik.");

        int n = values.size();
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / n;
        double median = quantile(values, 0.5);
        Map<Double, Long> frequency = new HashMap<>();
        values.forEach(v -> frequency.merge(v, 1L, Long::sum));
        long maxFrequency = frequency.values().stream().max(Long::compareTo).orElse(1L);
        double mode = frequency.entrySet().stream().filter(e -> e.getValue() == maxFrequency).map(Map.Entry::getKey).min(Comparator.naturalOrder()).orElse(values.get(0));
        double min = values.get(0), max = values.get(n - 1);
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / n;
        double sd = Math.sqrt(variance);
        double m3 = values.stream().mapToDouble(v -> Math.pow(v - mean, 3)).sum() / n;
        double m4 = values.stream().mapToDouble(v -> Math.pow(v - mean, 4)).sum() / n;
        double skewness = sd == 0 ? 0 : m3 / Math.pow(sd, 3);
        double kurtosis = variance == 0 ? 0 : m4 / Math.pow(variance, 2) - 3;
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("column", column); result.put("count", n); result.put("sum", sum); result.put("mean", mean); result.put("median", median); result.put("mode", mode);
        result.put("minimum", min); result.put("maximum", max); result.put("range", max - min); result.put("variance", variance); result.put("standardDeviation", sd);
        result.put("q1", quantile(values, 0.25)); result.put("q2", median); result.put("q3", quantile(values, 0.75)); result.put("iqr", quantile(values, 0.75) - quantile(values, 0.25));
        result.put("coefficientOfVariation", mean == 0 ? null : sd / Math.abs(mean) * 100); result.put("skewness", skewness); result.put("kurtosis", kurtosis);
        result.put("modeFrequency", maxFrequency);
        return result;
    }

    public List<Map<String, Object>> describeAll(DataTable table, List<String> columns) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String column : columns) results.add(describe(table, column));
        return results;
    }

    public static double quantile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return Double.NaN;
        double position = (sorted.size() - 1) * p;
        int lower = (int) Math.floor(position), upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * (position - lower);
    }
}
