package com.dataanalyzer.service;

import com.dataanalyzer.model.DataTable;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DatasetParser {
    private final long maxRows;
    private final int maxColumns;
    private final long maxBytes;

    public DatasetParser(@Value("${app.max-rows:100000}") long maxRows,
                         @Value("${app.max-columns:200}") int maxColumns,
                         @Value("${app.max-upload-mb:10}") long maxUploadMb) {
        this.maxRows = maxRows;
        this.maxColumns = maxColumns;
        this.maxBytes = maxUploadMb * 1024L * 1024L;
    }

    public DataTable parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new FileValidationException("File kosong. Pilih CSV atau XLSX yang berisi data.");
        if (file.getSize() > maxBytes) throw new FileValidationException("Ukuran file melewati batas " + (maxBytes / 1024 / 1024) + " MB.");
        String name = safeName(file.getOriginalFilename());
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = file.getBytes();
            List<List<String>> raw;
            if (lower.endsWith(".xlsx")) {
                if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw new FileValidationException("Isi file tidak cocok dengan format XLSX. Periksa file lalu coba lagi.");
                raw = readWorkbook(bytes);
            } else if (lower.endsWith(".csv")) {
                if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') throw new FileValidationException("Isi file tampaknya workbook, bukan CSV. Pilih file dengan ekstensi yang sesuai.");
                raw = readCsv(bytes);
            } else {
                throw new FileValidationException("Format belum didukung. Gunakan file .xlsx atau .csv.");
            }
            return toTable(raw);
        } catch (FileValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileValidationException("File tidak dapat dibaca. Periksa format, sheet pertama, dan struktur datanya.");
        }
    }

    private List<List<String>> readWorkbook(byte[] bytes) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw new FileValidationException("Workbook tidak memiliki sheet.");
            Sheet sheet = workbook.getSheetAt(0);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int lastRow = sheet.getLastRowNum();
            if (lastRow + 1L > maxRows + 1L) throw new FileValidationException("Dataset melewati batas " + maxRows + " baris.");
            int width = 0;
            for (Row row : sheet) width = Math.max(width, row.getLastCellNum());
            if (width < 1) throw new FileValidationException("Sheet pertama tidak memiliki kolom data.");
            if (width > maxColumns) throw new FileValidationException("Dataset melewati batas " + maxColumns + " kolom.");
            for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                List<String> cells = new ArrayList<>(width);
                for (int col = 0; col < width; col++) {
                    Cell cell = row == null ? null : row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
                    if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        value = cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    }
                    cells.add(value == null ? "" : value.trim());
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private List<List<String>> readCsv(byte[] bytes) throws IOException {
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
        String first = content.lines().findFirst().orElse("");
        char delimiter = detectDelimiter(first);
        List<List<String>> rows = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(true).get();
        try (CSVParser parser = CSVParser.parse(content, format)) {
            for (var record : parser) {
                if (rows.size() > maxRows) throw new FileValidationException("Dataset melewati batas " + maxRows + " baris.");
                List<String> row = new ArrayList<>(record.size());
                for (String cell : record) row.add(cell == null ? "" : cell.trim());
                if (row.size() > maxColumns) throw new FileValidationException("Dataset melewati batas " + maxColumns + " kolom.");
                rows.add(row);
            }
        }
        return rows;
    }

    private char detectDelimiter(String firstLine) {
        char chosen = ',';
        int max = 0;
        for (char delimiter : new char[]{',', ';', '\t'}) {
            int count = 0;
            boolean quoted = false;
            for (int i = 0; i < firstLine.length(); i++) {
                char c = firstLine.charAt(i);
                if (c == '"') quoted = !quoted;
                else if (!quoted && c == delimiter) count++;
            }
            if (count > max) { max = count; chosen = delimiter; }
        }
        return chosen;
    }

    private DataTable toTable(List<List<String>> raw) {
        if (raw.size() < 2) throw new FileValidationException("Dataset harus memiliki header dan setidaknya satu baris data.");
        int width = raw.stream().mapToInt(List::size).max().orElse(0);
        if (width == 0 || width > maxColumns) throw new FileValidationException("Jumlah kolom tidak valid atau melewati batas.");
        List<String> columns = uniqueHeaders(raw.get(0), width);
        List<List<String>> normalized = new ArrayList<>();
        for (int r = 1; r < raw.size(); r++) {
            List<String> row = raw.get(r);
            List<String> copy = new ArrayList<>(width);
            for (int c = 0; c < width; c++) copy.add(c < row.size() ? row.get(c) : "");
            if (copy.stream().anyMatch(v -> !blank(v))) normalized.add(copy);
        }
        if (normalized.isEmpty()) throw new FileValidationException("Tidak ada baris data setelah header.");
        if (normalized.size() > maxRows) throw new FileValidationException("Dataset melewati batas " + maxRows + " baris.");

        List<Map<String, Object>> result = new ArrayList<>(normalized.size());
        for (List<String> row : normalized) result.add(new LinkedHashMap<>());
        for (int c = 0; c < width; c++) {
            List<String> values = new ArrayList<>();
            for (List<String> row : normalized) {
                String candidate = row.get(c);
                if (!blank(candidate)) values.add(candidate);
            }
            boolean allBoolean = !values.isEmpty() && values.stream().allMatch(this::isBoolean);
            boolean allNumeric = !values.isEmpty() && values.stream().allMatch(v -> parseNumber(v) != null);
            boolean allDate = !values.isEmpty() && values.stream().allMatch(v -> parseDate(v) != null);
            for (int r = 0; r < normalized.size(); r++) {
                String text = normalized.get(r).get(c);
                Object value = null;
                if (!blank(text)) {
                    if (allBoolean) value = switch (text.trim().toLowerCase(Locale.ROOT)) { case "true", "ya" -> true; default -> false; };
                    else if (allNumeric) value = parseNumber(text);
                    else if (allDate) value = parseDate(text).toString();
                    else value = text;
                }
                result.get(r).put(columns.get(c), value);
            }
        }
        return new DataTable(columns, result);
    }

    private List<String> uniqueHeaders(List<String> header, int width) {
        List<String> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < width; i++) {
            String base = i < header.size() ? header.get(i).trim() : "";
            if (base.isBlank()) base = "Kolom " + (i + 1);
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate.toLowerCase(Locale.ROOT))) candidate = base + " (" + suffix++ + ")";
            result.add(candidate);
        }
        return result;
    }

    public static boolean blank(Object value) { return value == null || value.toString().isBlank(); }
    private boolean isBoolean(String value) { return Set.of("true", "false", "ya", "tidak").contains(value.trim().toLowerCase(Locale.ROOT)); }
    private LocalDate parseDate(String text) {
        String value = text.trim();
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("M/d/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"))) {
            try { return LocalDate.parse(value, formatter); } catch (DateTimeParseException ignored) { }
        }
        return null;
    }

    public static Double parseNumber(String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace("\u00a0", "").replaceAll("\\s+", "");
        if (value.isBlank()) return null;
        boolean negative = value.startsWith("(") && value.endsWith(")");
        if (negative) value = "-" + value.substring(1, value.length() - 1);
        value = value.replaceAll("[^0-9,\\.\\-+]", "");
        if (value.isBlank() || value.equals("-") || value.equals("+")) return null;
        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            if (comma > dot) value = value.replace(".", "").replace(',', '.');
            else value = value.replace(",", "");
        } else if (comma >= 0) {
            int digitsAfter = value.length() - comma - 1;
            value = digitsAfter > 0 && digitsAfter <= 2 ? value.replace(',', '.') : value.replace(",", "");
        } else if (dot >= 0) {
            int digitsAfter = value.length() - dot - 1;
            int digitsBefore = dot - (value.startsWith("-") || value.startsWith("+") ? 1 : 0);
            boolean groupedThousands = digitsAfter == 3 && digitsBefore >= 1 && digitsBefore <= 3;
            if (value.indexOf('.') != dot || groupedThousands) value = value.replace(".", "");
        }
        try { double parsed = Double.parseDouble(value); return Double.isFinite(parsed) ? parsed : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String safeName(String input) {
        if (input == null || input.isBlank()) return "dataset";
        String name = input.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        return name.isBlank() ? "dataset" : name;
    }

    public static final class FileValidationException extends RuntimeException {
        public FileValidationException(String message) { super(message); }
    }
}
