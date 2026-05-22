package com.bdic.text;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Document text extractor.
 *
 * <p>Extracts plain text from readable text, PDF, Word, and spreadsheet documents
 * so searchable keywords can be generated automatically later.</p>
 */
public final class DocumentTextExtractor {

    /** Utility class; instantiation is not needed. */
    private DocumentTextExtractor() {
    }

    /**
     * Attempts to extract indexable text according to MIME type, media category, and file extension.
     *
     * <p>Returns an empty string when the type is unrecognized or extraction fails, avoiding upload interruption due to text preprocessing failure.</p>
     */
    public static String extract(Path path, String mimeType, String mediaType) {
        if (path == null || !Files.exists(path)) {
            return "";
        }

        // Normalize to lowercase so later type checks can ignore case differences.
        String normalizedMimeType = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);

        try {
            // PDF, Word, and spreadsheets require dedicated parsers; ordinary text files are read directly as UTF-8.
            if (isPdf(normalizedMimeType, fileName)) {
                return extractPdf(path);
            }
            if (isWord(normalizedMimeType, fileName)) {
                return extractWord(path, fileName);
            }
            if (isSpreadsheet(normalizedMimeType, fileName)) {
                return extractSpreadsheet(path);
            }
            if (isPlainTextLike(mediaType, normalizedMimeType, fileName)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "";
        }

        return "";
    }

    /**
     * Checks whether a file should be parsed as PDF.
     */
    private static boolean isPdf(String mimeType, String fileName) {
        return "application/pdf".equals(mimeType) || fileName.endsWith(".pdf");
    }

    /**
     * Checks whether a file should be parsed as a Word document, including legacy doc and newer docx files.
     */
    private static boolean isWord(String mimeType, String fileName) {
        return "application/msword".equals(mimeType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType)
                || fileName.endsWith(".doc")
                || fileName.endsWith(".docx");
    }

    /**
     * Checks whether a file should be parsed as a spreadsheet, including legacy xls and newer xlsx files.
     */
    private static boolean isSpreadsheet(String mimeType, String fileName) {
        return "application/vnd.ms-excel".equals(mimeType)
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mimeType)
                || mimeType.contains("spreadsheet")
                || mimeType.contains("excel")
                || fileName.endsWith(".xls")
                || fileName.endsWith(".xlsx");
    }

    /**
     * JSON files are handled as readable text in this project to support keyword extraction and search-result preview.
     */
    private static boolean isPlainTextLike(String mediaType, String mimeType, String fileName) {
        return "text".equalsIgnoreCase(mediaType)
                || mimeType.startsWith("text/")
                || "application/json".equals(mimeType)
                || mimeType.endsWith("+json")
                || fileName.endsWith(".json")
                || fileName.endsWith(".csv");
    }

    /**
     * Extracts page text from a PDF file with PDFBox.
     */
    private static String extractPdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Selects POI's docx or doc parser by extension and extracts Word text.
     */
    private static String extractWord(Path path, String fileName) throws IOException {
        if (fileName.endsWith(".docx")) {
            // XWPF handles docx in Office Open XML format.
            try (InputStream inputStream = Files.newInputStream(path);
                 XWPFDocument document = new XWPFDocument(inputStream);
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                return extractor.getText();
            }
        }

        // HWPF handles the older binary doc format.
        try (InputStream inputStream = Files.newInputStream(path);
             HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * Extracts displayed cell values from all spreadsheet sheets.
     */
    private static String extractSpreadsheet(Path path) throws IOException {
        StringBuilder extracted = new StringBuilder();
        try (InputStream inputStream = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (sheet == null) {
                    continue;
                }
                appendToken(extracted, sheet.getSheetName());
                for (Row row : sheet) {
                    if (row == null) {
                        continue;
                    }
                    for (Cell cell : row) {
                        appendToken(extracted, formatCell(cell, formatter, evaluator));
                    }
                }
                extracted.append('\n');
            }
        }
        return extracted.toString();
    }

    /**
     * Formats a cell with formula evaluation when possible, falling back to the displayed value.
     */
    private static String formatCell(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException ignored) {
            return formatter.formatCellValue(cell);
        }
    }

    /**
     * Appends a non-blank value separated by whitespace so downstream tokenization can split it normally.
     */
    private static void appendToken(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty() && target.charAt(target.length() - 1) != '\n') {
            target.append(' ');
        }
        target.append(value.trim());
    }
}
