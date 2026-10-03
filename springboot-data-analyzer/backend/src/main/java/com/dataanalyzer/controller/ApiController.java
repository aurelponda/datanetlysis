package com.dataanalyzer.controller;

import com.dataanalyzer.model.DataTable;
import com.dataanalyzer.model.DatasetSession;
import com.dataanalyzer.service.AnalysisService;
import com.dataanalyzer.service.DatasetParser;
import com.dataanalyzer.service.DatasetStore;
import com.dataanalyzer.service.StatisticsService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final DatasetParser parser;
    private final DatasetStore store;
    private final AnalysisService analysis;
    private final StatisticsService statistics;

    public ApiController(DatasetParser parser, DatasetStore store, AnalysisService analysis, StatisticsService statistics) {
        this.parser = parser; this.store = store; this.analysis = analysis; this.statistics = statistics;
    }

    @PostMapping(value = "/datasets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        DataTable table = parser.parse(file);
        DatasetSession session = store.create(safeFileName(file.getOriginalFilename()), file.getSize(), extension(file.getOriginalFilename()), table);
        return metadata(session, table, 25);
    }

    @GetMapping("/datasets/{id}")
    public Map<String, Object> getDataset(@PathVariable String id) {
        DatasetSession session = store.require(id);
        return metadata(session, session.current(), 25);
    }

    @GetMapping("/datasets/{id}/rows")
    public Map<String, Object> rows(@PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String filterColumn,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String value) {
        DataTable table = store.require(id).current();
        int safePage = Math.max(1, page), safeSize = Math.max(1, Math.min(100, pageSize));
        List<Map<String, Object>> all = analysis.page(table, 1, Math.max(1, table.rows().size()), search, sortBy, direction, filterColumn, operator, value);
        List<Map<String, Object>> selected = analysis.page(table, safePage, safeSize, search, sortBy, direction, filterColumn, operator, value);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", table.columns()); result.put("rows", selected); result.put("page", safePage); result.put("pageSize", safeSize);
        result.put("totalRows", all.size()); result.put("totalPages", (int) Math.ceil(all.size() / (double) safeSize));
        return result;
    }

    @GetMapping("/datasets/{id}/statistics")
    public Map<String, Object> statistics(@PathVariable String id, @RequestParam(required = false) String columns) {
        DataTable table = store.require(id).current();
        List<String> requested = columns == null || columns.isBlank()
                ? analysis.columns(table).stream().filter(column -> "numeric".equals(column.get("type"))).map(column -> column.get("name").toString()).toList()
                : java.util.Arrays.stream(columns.split(",")).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        List<Map<String, Object>> results = analysisStats(table, requested);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("statistics", results);
        return result;
    }

    private List<Map<String, Object>> analysisStats(DataTable table, List<String> requested) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String column : requested) {
            Map<String, Object> item = statistics.describe(table, column);
            if (item.containsKey("mean")) item.put("interpretation", analysis.interpretation(item));
            result.add(item);
        }
        return result;
    }

    @PostMapping("/datasets/{id}/charts")
    public Map<String, Object> chart(@PathVariable String id, @RequestBody Map<String, Object> request) {
        return analysis.chart(store.require(id).current(), request);
    }

    @GetMapping("/datasets/{id}/missing")
    public Map<String, Object> missing(@PathVariable String id) { return analysis.missing(store.require(id).current()); }

    @PostMapping("/datasets/{id}/clean")
    public Map<String, Object> clean(@PathVariable String id, @RequestBody Map<String, Object> request) {
        DatasetSession session = store.require(id);
        Map<String, Object> outcome = analysis.clean(session.current(), request);
        DataTable table = (DataTable) outcome.get("table");
        session.update(table);
        Map<String, Object> result = metadata(session, table, 25);
        result.put("cleaning", outcome.get("summary"));
        return result;
    }

    @PostMapping("/datasets/{id}/reset")
    public Map<String, Object> reset(@PathVariable String id) {
        DatasetSession session = store.require(id); session.reset();
        return metadata(session, session.current(), 25);
    }

    @DeleteMapping("/datasets/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) { store.delete(id); return ResponseEntity.noContent().build(); }

    @GetMapping("/datasets/{id}/export.csv")
    public ResponseEntity<ByteArrayResource> exportCsv(@PathVariable String id) {
        DatasetSession session = store.require(id); String csv = toCsv(session.current());
        return download(csv.getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8", baseName(session.fileName()) + "-data.csv");
    }

    @GetMapping("/datasets/{id}/export.xlsx")
    public ResponseEntity<ByteArrayResource> exportXlsx(@PathVariable String id) throws IOException {
        DatasetSession session = store.require(id);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeWorkbook(workbook, session.current()); workbook.write(output);
            return download(output.toByteArray(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", baseName(session.fileName()) + "-data.xlsx");
        }
    }

    @GetMapping("/datasets/{id}/summary.csv")
    public ResponseEntity<ByteArrayResource> exportSummary(@PathVariable String id) {
        DataTable table = store.require(id).current();
        List<String> numeric = analysis.columns(table).stream().filter(column -> "numeric".equals(column.get("type"))).map(column -> column.get("name").toString()).toList();
        StringBuilder csv = new StringBuilder("Kolom,Count,Sum,Mean,Median,Mode,Minimum,Maximum,Range,Variance,Standard Deviation,Q1,Q2,Q3,IQR,Skewness,Kurtosis,Interpretasi\r\n");
        for (Map<String, Object> item : analysisStats(table, numeric)) {
            List<Object> cells = List.of(item.get("column"), item.get("count"), item.get("sum"), item.get("mean"), item.get("median"), item.get("mode"), item.get("minimum"), item.get("maximum"), item.get("range"), item.get("variance"), item.get("standardDeviation"), item.get("q1"), item.get("q2"), item.get("q3"), item.get("iqr"), item.get("skewness"), item.get("kurtosis"), item.get("interpretation"));
            csv.append(cells.stream().map(value -> csvCell(value == null ? "" : value.toString())).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return download(csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8", baseName(store.require(id).fileName()) + "-summary.csv");
    }

    private Map<String, Object> metadata(DatasetSession session, DataTable table, int previewSize) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("id", session.id()); result.put("fileName", session.fileName());
        result.put("fileSize", session.fileSize()); result.put("fileType", session.fileType()); result.put("rows", table.rows().size()); result.put("columns", table.columns().size());
        result.put("columnNames", table.columns()); result.put("columnMeta", analysis.columns(table)); result.put("missing", analysis.missing(table));
        result.put("preview", table.rows().stream().limit(previewSize).toList()); result.put("previewCount", Math.min(previewSize, table.rows().size()));
        return result;
    }

    private String toCsv(DataTable table) {
        StringBuilder output = new StringBuilder();
        output.append(table.columns().stream().map(ApiController::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for (Map<String, Object> row : table.rows()) output.append(table.columns().stream().map(column -> csvCell(row.get(column) == null ? "" : row.get(column).toString())).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        return output.toString();
    }

    private void writeWorkbook(XSSFWorkbook workbook, DataTable table) {
        Sheet sheet = workbook.createSheet("Dataset");
        Row header = sheet.createRow(0);
        for (int c = 0; c < table.columns().size(); c++) header.createCell(c).setCellValue(table.columns().get(c));
        for (int r = 0; r < table.rows().size(); r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < table.columns().size(); c++) {
                Object value = table.rows().get(r).get(table.columns().get(c));
                if (value == null) continue;
                var cell = row.createCell(c);
                if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                else if (value instanceof Boolean bool) cell.setCellValue(bool);
                else cell.setCellValue(value.toString());
            }
        }
    }

    private ResponseEntity<ByteArrayResource> download(byte[] bytes, String contentType, String filename) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(bytes));
    }

    private static String csvCell(String value) {
        String leading = value.stripLeading();
        boolean numeric = leading.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");
        boolean formula = !leading.isEmpty() && (leading.startsWith("=") || leading.startsWith("+") || leading.startsWith("-") || leading.startsWith("@")) && !numeric;
        boolean controlPrefix = !value.isEmpty() && (value.charAt(0) == '\t' || value.charAt(0) == '\r' || value.charAt(0) == '\n');
        if (formula || controlPrefix) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    private static String extension(String name) { return name != null && name.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? "XLSX" : "CSV"; }
    private static String safeFileName(String name) { if (name == null || name.isBlank()) return "dataset.csv"; String cleaned = name.replace('\\', '/'); return cleaned.substring(cleaned.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._ -]", "_"); }
    private static String baseName(String name) { return name.replaceFirst("(?i)\\.(csv|xlsx)$", "").replaceAll("[^\\p{L}\\p{N}._-]", "_"); }
}
