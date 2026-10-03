package com.dataanalyzer.service;

import com.dataanalyzer.model.DataTable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AnalysisService {
    private final StatisticsService statistics;
    public AnalysisService(StatisticsService statistics) { this.statistics = statistics; }

    public List<Map<String, Object>> columns(DataTable table) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : table.columns()) {
            List<Object> values = table.rows().stream().map(row -> row.get(name)).filter(v -> !DatasetParser.blank(v)).toList();
            long numeric = values.stream().filter(v -> DatasetParser.parseNumber(v.toString()) != null).count();
            long bool = values.stream().filter(v -> v instanceof Boolean).count();
            long date = values.stream().filter(v -> v instanceof String s && s.matches("\\d{4}-\\d{2}-\\d{2}")).count();
            String type = values.isEmpty() ? "text" : numeric == values.size() ? "numeric" : bool == values.size() ? "boolean" : date == values.size() ? "date" : "text";
            Map<String, Object> item = new LinkedHashMap<>(); item.put("name", name); item.put("type", type); item.put("nonEmpty", values.size()); item.put("missing", table.rows().size() - values.size());
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> missing(DataTable table) {
        List<Map<String, Object>> byColumn = new ArrayList<>(); long total = 0;
        for (String column : table.columns()) {
            long missing = table.rows().stream().filter(row -> DatasetParser.blank(row.get(column))).count(); total += missing;
            Map<String, Object> item = new LinkedHashMap<>(); item.put("column", column); item.put("count", missing); item.put("percentage", table.rows().isEmpty() ? 0 : missing * 100.0 / table.rows().size()); byColumn.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("total", total); result.put("percentage", table.rows().isEmpty() || table.columns().isEmpty() ? 0 : total * 100.0 / (table.rows().size() * table.columns().size())); result.put("columns", byColumn);
        return result;
    }

    public List<Map<String, Object>> page(DataTable table, int page, int pageSize, String search, String sortBy, String direction,
                                          String filterColumn, String operator, String filterValue) {
        List<Map<String, Object>> filtered = new ArrayList<>(table.rows());
        if (search != null && !search.isBlank()) {
            String needle = search.toLowerCase(Locale.ROOT);
            filtered.removeIf(row -> row.values().stream().noneMatch(value -> value != null && value.toString().toLowerCase(Locale.ROOT).contains(needle)));
        }
        if (filterColumn != null && !filterColumn.isBlank()) {
            if (!table.columns().contains(filterColumn)) throw new IllegalArgumentException("Kolom filter tidak ditemukan.");
            String op = operator == null ? "=" : operator;
            filtered.removeIf(row -> !matches(row.get(filterColumn), op, filterValue == null ? "" : filterValue));
        }
        if (sortBy != null && table.columns().contains(sortBy)) {
            Comparator<Map<String, Object>> comparator = (a, b) -> compare(a.get(sortBy), b.get(sortBy));
            if ("desc".equalsIgnoreCase(direction)) comparator = comparator.reversed();
            filtered.sort(comparator);
        }
        int from = Math.min(Math.max(0, page - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        return filtered.subList(from, to);
    }

    private boolean matches(Object raw, String operator, String expected) {
        if (operator.equalsIgnoreCase("contains")) return raw != null && raw.toString().toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
        if (operator.equalsIgnoreCase("startsWith")) return raw != null && raw.toString().toLowerCase(Locale.ROOT).startsWith(expected.toLowerCase(Locale.ROOT));
        if (raw == null) return false;
        Double actualNumber = DatasetParser.parseNumber(raw.toString()), expectedNumber = DatasetParser.parseNumber(expected);
        int cmp = actualNumber != null && expectedNumber != null ? Double.compare(actualNumber, expectedNumber) : raw.toString().compareToIgnoreCase(expected);
        return switch (operator) { case "=" -> cmp == 0; case "!=", "≠" -> cmp != 0; case ">" -> cmp > 0; case "<" -> cmp < 0; case ">=", "≥" -> cmp >= 0; case "<=", "≤" -> cmp <= 0; default -> throw new IllegalArgumentException("Operator filter tidak didukung."); };
    }

    private int compare(Object a, Object b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        Double an = DatasetParser.parseNumber(a.toString()), bn = DatasetParser.parseNumber(b.toString());
        return an != null && bn != null ? Double.compare(an, bn) : a.toString().compareToIgnoreCase(b.toString());
    }

    public Map<String, Object> chart(DataTable table, Map<String, Object> request) {
        String type = String.valueOf(request.getOrDefault("type", "histogram")).toLowerCase(Locale.ROOT);
        if (!List.of("histogram", "bar", "line", "pie", "scatter", "box").contains(type)) throw new IllegalArgumentException("Jenis grafik tidak didukung.");
        String column = String.valueOf(request.getOrDefault("column", table.columns().get(0)));
        if (!table.columns().contains(column)) throw new IllegalArgumentException("Kolom grafik tidak ditemukan.");
        Map<String, Object> result = new LinkedHashMap<>(); result.put("type", type); result.put("column", column);
        if (type.equals("scatter")) {
            String xColumn = String.valueOf(request.getOrDefault("xColumn", column));
            String yColumn = String.valueOf(request.getOrDefault("yColumn", column));
            if (!table.columns().contains(xColumn) || !table.columns().contains(yColumn)) throw new IllegalArgumentException("Kolom sumbu grafik tidak ditemukan.");
            List<Map<String, Object>> points = table.rows().stream().map(row -> {
                Double x = DatasetParser.parseNumber(row.get(xColumn) == null ? null : row.get(xColumn).toString());
                Double y = DatasetParser.parseNumber(row.get(yColumn) == null ? null : row.get(yColumn).toString());
                return x == null || y == null ? null : Map.<String, Object>of("x", x, "y", y);
            }).filter(p -> p != null).limit(5000).toList();
            result.put("xColumn", xColumn); result.put("yColumn", yColumn); result.put("points", points); return result;
        }
        if (type.equals("box")) { result.put("statistics", statistics.describe(table, column)); return result; }
        List<Object> nonNull = table.rows().stream().map(row -> row.get(column)).filter(v -> !DatasetParser.blank(v)).toList();
        if (type.equals("histogram")) {
            List<Double> numeric = nonNull.stream().map(v -> DatasetParser.parseNumber(v.toString())).filter(v -> v != null).sorted().toList();
            if (numeric.isEmpty()) throw new IllegalArgumentException("Histogram memerlukan kolom numerik.");
            int bins = Math.max(3, Math.min(40, Integer.parseInt(String.valueOf(request.getOrDefault("bins", 10)))));
            double min = numeric.get(0), max = numeric.get(numeric.size() - 1), width = max == min ? 1 : (max - min) / bins;
            int[] counts = new int[bins]; for (double value : numeric) counts[Math.min(bins - 1, (int) ((value - min) / width))]++;
            List<String> labels = new ArrayList<>(); for (int i = 0; i < bins; i++) labels.add(String.format(Locale.ROOT, "%.2f–%.2f", min + width * i, min + width * (i + 1)));
            result.put("labels", labels); result.put("values", counts); return result;
        }
        if (type.equals("line")) {
            List<Object> numeric = nonNull.stream().map(v -> DatasetParser.parseNumber(v.toString())).filter(v -> v != null).limit(1000).map(v -> (Object) v).toList();
            result.put("labels", java.util.stream.IntStream.rangeClosed(1, numeric.size()).mapToObj(i -> Integer.toString(i)).toList()); result.put("values", numeric); return result;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object value : nonNull) counts.merge(value.toString(), 1L, Long::sum);
        List<Map.Entry<String, Long>> ordered = counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(type.equals("pie") ? 12 : 30).toList();
        result.put("labels", ordered.stream().map(Map.Entry::getKey).toList()); result.put("values", ordered.stream().map(Map.Entry::getValue).toList());
        return result;
    }

    public Map<String, Object> clean(DataTable current, Map<String, Object> request) {
        List<String> columns = new ArrayList<>(current.columns());
        List<Map<String, Object>> rows = current.rows().stream().map(LinkedHashMap::new).map(r -> (Map<String, Object>) r).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        String strategy = String.valueOf(request.getOrDefault("missingStrategy", "ignore"));
        List<String> requestedColumns = request.get("removeColumns") instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of();
        for (String column : requestedColumns) {
            if (!columns.contains(column)) throw new IllegalArgumentException("Kolom yang akan dihapus tidak ditemukan.");
        }
        columns.removeAll(requestedColumns); rows.forEach(row -> requestedColumns.forEach(row::remove));
        if (columns.isEmpty()) throw new IllegalArgumentException("Dataset harus menyisakan setidaknya satu kolom.");

        if (request.get("renames") instanceof Map<?, ?> renameMap) {
            for (Map.Entry<?, ?> entry : renameMap.entrySet()) {
                String oldName = entry.getKey().toString(), newName = entry.getValue().toString().trim();
                if (!columns.contains(oldName) || newName.isBlank()) throw new IllegalArgumentException("Nama kolom baru tidak valid.");
                int index = columns.indexOf(oldName);
                if (columns.stream().anyMatch(name -> !name.equals(oldName) && name.equalsIgnoreCase(newName))) throw new IllegalArgumentException("Nama kolom baru sudah digunakan.");
                columns.set(index, newName); rows.forEach(row -> row.put(newName, row.remove(oldName)));
            }
        }
        if (strategy.equals("removeRows")) rows.removeIf(row -> columns.stream().anyMatch(column -> DatasetParser.blank(row.get(column))));
        else if (List.of("mean", "median", "mode").contains(strategy)) {
            for (String column : columns) {
                List<Object> present = rows.stream().map(row -> row.get(column)).filter(v -> !DatasetParser.blank(v)).toList();
                if (present.isEmpty()) continue;
                Object replacement = replacement(present, strategy);
                if (replacement != null) rows.forEach(row -> { if (DatasetParser.blank(row.get(column))) row.put(column, replacement); });
            }
        } else if (!strategy.equals("ignore")) throw new IllegalArgumentException("Strategi missing values tidak dikenal.");

        if (Boolean.TRUE.equals(request.get("removeDuplicates"))) {
            java.util.Set<List<Object>> seen = new java.util.HashSet<>();
            rows.removeIf(row -> !seen.add(columns.stream().map(row::get).toList()));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Pembersihan akan menghapus semua baris; perubahan dibatalkan.");
        List<Map<String, Object>> safeRows = rows.stream().map(row -> { Map<String, Object> ordered = new LinkedHashMap<>(); columns.forEach(column -> ordered.put(column, row.get(column))); return ordered; }).toList();
        return Map.of("table", new DataTable(columns, safeRows), "summary", summary(current, columns, rows.size()));
    }

    private Object replacement(List<Object> present, String strategy) {
        List<Double> numbers = present.stream().map(v -> DatasetParser.parseNumber(v.toString())).filter(v -> v != null).sorted().toList();
        if (strategy.equals("mean")) return numbers.isEmpty() ? null : numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (strategy.equals("median")) return numbers.isEmpty() ? null : StatisticsService.quantile(numbers, .5);
        Map<Object, Long> frequency = new java.util.HashMap<>(); present.forEach(value -> frequency.merge(value, 1L, Long::sum));
        long max = frequency.values().stream().max(Long::compareTo).orElse(0L);
        return frequency.entrySet().stream().filter(entry -> entry.getValue() == max).map(Map.Entry::getKey).min(Comparator.comparing(Object::toString)).orElse(null);
    }

    private Map<String, Object> summary(DataTable before, List<String> columns, int rowCount) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("rowsBefore", before.rows().size()); result.put("rowsAfter", rowCount); result.put("columnsAfter", columns.size()); return result;
    }

    public String interpretation(Map<String, Object> s) {
        if (!s.containsKey("mean")) return "Kolom " + s.get("column") + " belum memiliki nilai numerik yang dapat dihitung.";
        String column = String.valueOf(s.get("column")); double mean = number(s, "mean"), median = number(s, "median"), sd = number(s, "standardDeviation");
        double skew = number(s, "skewness");
        String spread = sd == 0 ? "tidak ada variasi pada nilai yang tersedia" : sd / Math.max(Math.abs(mean), 1e-12) < .1 ? "relatif rendah dibandingkan rata-ratanya" : sd / Math.max(Math.abs(mean), 1e-12) < .3 ? "berada pada tingkat sedang dibandingkan rata-ratanya" : "cukup besar dibandingkan rata-ratanya";
        String shape = Math.abs(skew) < .3 ? "relatif simetris menurut skewness" : skew > 0 ? "memiliki ekor yang lebih panjang ke kanan menurut skewness" : "memiliki ekor yang lebih panjang ke kiri menurut skewness";
        return String.format(Locale.forLanguageTag("id-ID"), "Pada kolom %s, rata-rata %.2f dan median %.2f dihitung dari %d nilai numerik. Standar deviasi %.2f menunjukkan variasi %s. Nilai terendah %.2f dan tertinggi %.2f menghasilkan rentang %.2f. Distribusi terlihat %s (skewness %.2f); periksa histogram atau box plot untuk konteks lebih lanjut.", column, mean, median, ((Number) s.get("count")).intValue(), sd, spread, number(s, "minimum"), number(s, "maximum"), number(s, "range"), shape, skew);
    }

    private double number(Map<String, Object> map, String key) { return ((Number) map.get(key)).doubleValue(); }
}
