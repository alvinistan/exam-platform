package com.leenex.exam.catalog.importing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads the approved import templates (FR-QB-08): UTF-8 CSV (RFC 4180, comma separated, quoted fields may hold commas,
 * quotes and line breaks) or the first sheet of an XLSX workbook. The first row holds the column names.
 */
public final class ImportFile {

    public static final List<String> COLUMNS = List.of("subject", "module", "topic", "type", "question", "option_a",
            "option_b", "option_c", "option_d", "option_e", "correct", "explanation", "key_points", "definitions",
            "difficulty", "seconds", "tags", "marks");

    static final int MAX_ROWS = 2000;

    /** One data row: its number as the admin sees it in a spreadsheet (header = row 1), and its cells by column. */
    public record Row(int number, Map<String, String> cells) {
        public String get(String column) {
            String value = cells.get(column);
            return value == null ? "" : value.strip();
        }
    }

    public static final class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    private ImportFile() {
    }

    public static List<Row> read(String fileName, byte[] bytes) throws FormatException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        List<List<String>> table;
        if (lower.endsWith(".xlsx")) {
            table = readXlsx(bytes);
        } else if (lower.endsWith(".csv")) {
            table = readCsv(bytes);
        } else {
            throw new FormatException("Upload a .csv or .xlsx file made from the template.");
        }
        if (table.isEmpty()) {
            throw new FormatException("The file is empty.");
        }
        List<String> header = table.getFirst().stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        List<String> missing = COLUMNS.stream().filter(c -> !header.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new FormatException("The header row is missing: " + String.join(", ", missing)
                    + ". Start from the template.");
        }
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            if (cells.stream().allMatch(String::isBlank)) {
                continue; // blank lines are ignored
            }
            Map<String, String> byColumn = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < cells.size(); c++) {
                if (!header.get(c).isEmpty()) { // spreadsheets often carry empty columns after the last one
                    byColumn.put(header.get(c), cells.get(c));
                }
            }
            rows.add(new Row(r + 1, byColumn));
        }
        if (rows.size() > MAX_ROWS) {
            throw new FormatException("A file can hold at most " + MAX_ROWS + " questions; this one has " + rows.size() + ".");
        }
        return rows;
    }

    static List<List<String>> readCsv(byte[] bytes) throws FormatException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new FormatException("Save the CSV as UTF-8 (in Excel: “CSV UTF-8”).");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                rows.add(row);
                row = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;
            } else {
                field.append(ch);
                fieldStarted = true;
            }
        }
        if (quoted) {
            throw new FormatException("A quoted field is never closed. Check for a missing \".");
        }
        if (fieldStarted || !row.isEmpty() || !field.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    static List<List<String>> readXlsx(byte[] bytes) throws FormatException {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();
            int last = Math.min(sheet.getLastRowNum(), MAX_ROWS + 1);
            for (int r = 0; r <= last; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < COLUMNS.size() + 5; c++) {
                        Cell cell = row.getCell(c, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                }
                rows.add(cells);
            }
            if (sheet.getLastRowNum() > MAX_ROWS + 1) {
                throw new FormatException("A file can hold at most " + MAX_ROWS + " questions.");
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            throw new FormatException("This isn't a readable .xlsx workbook.");
        }
    }

    /** The template: the header row plus one filled-in example the admin replaces. */
    public static List<List<String>> template() {
        return List.of(COLUMNS, List.of("Pharmacology", "Drug calculations", "Dosage by weight", "SINGLE",
                "A child weighs 20 kg. The dose is 5 mg/kg. How many mg do you give?", "50", "100", "150", "200", "",
                "B", "20 kg × 5 mg/kg = 100 mg.", "Multiply weight by the dose per kg|Check the units",
                "Dose per kg: the amount of drug for each kilogram of body weight", "SIMPLE", "60", "dosage|calculation",
                "1"));
    }

    static byte[] templateCsv() {
        StringBuilder csv = new StringBuilder("﻿");
        for (List<String> row : template()) {
            csv.append(String.join(",", row.stream().map(ImportFile::csvField).toList())).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] templateXlsx() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Questions");
            List<List<String>> rows = template();
            for (int r = 0; r < rows.size(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) {
                    row.createCell(c).setCellValue(rows.get(r).get(c));
                }
            }
            for (int c = 0; c < COLUMNS.size(); c++) {
                sheet.setColumnWidth(c, 20 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
