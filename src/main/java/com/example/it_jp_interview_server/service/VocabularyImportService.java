package com.example.it_jp_interview_server.service;

import com.example.it_jp_interview_server.model.VocabularyEntry;
import com.example.it_jp_interview_server.repository.VocabularyEntryRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class VocabularyImportService {
    public record ImportResult(List<VocabularyEntry> entries, int duplicatesSkipped) {
    }

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 500;
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]");
    private final VocabularyEntryRepository vocabularyRepository;
    private final AiAnalysisService aiAnalysisService;

    public VocabularyImportService(VocabularyEntryRepository vocabularyRepository, AiAnalysisService aiAnalysisService) {
        this.vocabularyRepository = vocabularyRepository;
        this.aiAnalysisService = aiAnalysisService;
    }

    public ImportResult importFile(Long userId, MultipartFile file, String setName, boolean useAi) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Chọn file không rỗng và nhỏ hơn 5 MB.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String content;
        if (name.endsWith(".pdf")) {
            try (var document = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new TableAwarePdfTextStripper();
                stripper.setSortByPosition(true);
                content = stripper.getText(document);
            }
        } else if (name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt")) {
            content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Chỉ hỗ trợ file CSV, TSV, TXT hoặc PDF.");
        }
        if (content.length() > 200_000) {
            throw new IllegalArgumentException("Nội dung file vượt quá giới hạn xử lý.");
        }

        List<List<String>> rows = new ArrayList<>();
        for (String line : content.replace("\uFEFF", "").split("\\R")) {
            if (rows.size() >= MAX_ROWS + 1) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            rows.add(parseCsvLine(trimmed));
        }
        if (rows.isEmpty()) {
            return new ImportResult(List.of(), 0);
        }

        List<String> headers = rows.get(0);
        boolean hasHeader = looksLikeHeader(headers, rows);
        Map<String, Integer> mapping;
        boolean needsAiMapping = useAi || (hasHeader && japaneseColumnIndex(headers) < 0);
        if (needsAiMapping) {
            if (!aiAnalysisService.isConfigured()) {
                throw new IllegalStateException("File có tiêu đề cột chưa nhận diện được. Hãy thiết lập AI_API_KEY để tự động xác định cột hoặc sửa tiêu đề theo mẫu.");
            }
            int dataStart = hasHeader ? 1 : 0;
            List<List<String>> samples = rows.subList(dataStart, Math.min(rows.size(), dataStart + 5));
            int columnCount = Math.min(15, Math.max(headers.size(), samples.stream().mapToInt(List::size).max().orElse(0)));
            List<String> aiHeaders = new ArrayList<>(hasHeader ? headers.subList(0, Math.min(headers.size(), columnCount)) : List.of());
            while (aiHeaders.size() < columnCount) {
                aiHeaders.add("Cột " + aiHeaders.size());
            }
            aiHeaders = aiHeaders.stream().map(value -> value.substring(0, Math.min(value.length(), 80))).toList();
            List<List<String>> safeSamples = samples.stream()
                    .map(row -> row.stream().limit(columnCount)
                            .map(value -> value.substring(0, Math.min(value.length(), 80)))
                            .toList())
                    .toList();
            try {
                mapping = aiAnalysisService.mapColumns(aiHeaders, safeSamples);
            } catch (Exception ex) {
                throw new IllegalStateException("AI không thể nhận diện cột trong file. Vui lòng thử lại hoặc tắt nhận diện AI.", ex);
            }
            if (mapping.getOrDefault("japanese", -1) < 0) {
                throw new IllegalStateException("AI không tìm được cột chứa từ tiếng Nhật. Hãy kiểm tra lại nội dung file.");
            }
        } else {
            mapping = detectColumns(rows, hasHeader);
        }

        List<VocabularyEntry> entries = new ArrayList<>();
        Set<String> existingWords = new HashSet<>(vocabularyRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(entry -> normalizeJapanese(entry.getJapanese()).toLowerCase(Locale.ROOT)).toList());
        int duplicatesSkipped = 0;
        int firstDataRow = hasHeader ? 1 : 0;
        for (int rowIndex = firstDataRow; rowIndex < rows.size() && entries.size() < MAX_ROWS; rowIndex++) {
            List<String> columns = rows.get(rowIndex);
            String japanese = limit(normalizeJapanese(column(columns, mapping.get("japanese"))), 200);
            if (japanese.isEmpty()) {
                continue;
            }
            if (!existingWords.add(japanese.toLowerCase(Locale.ROOT))) {
                duplicatesSkipped++;
                continue;
            }
            entries.add(vocabularyRepository.save(new VocabularyEntry(
                    userId,
                    limit(japanese, 200),
                    limit(setName == null || setName.isBlank() ? "Từ vựng đã nhập" : setName.trim(), 100),
                    limit(column(columns, mapping.get("reading")).trim(), 200),
                    limit(column(columns, mapping.get("vietnamese")).trim(), 500),
                    limit(column(columns, mapping.get("example")).trim(), 600)
            )));
        }
        return new ImportResult(entries, duplicatesSkipped);
    }

    private static Map<String, Integer> detectColumns(List<List<String>> rows, boolean hasHeader) {
        List<String> headers = rows.get(0);
        int japanese = -1;
        int reading = -1;
        int vietnamese = -1;
        int example = -1;
        if (hasHeader) {
            for (int i = 0; i < headers.size(); i++) {
                String header = normalizeHeader(headers.get(i));
                if (isJapaneseHeader(header)) japanese = i;
                else if (header.matches(".*(ふりがな|hiragana|reading|読み|kana|cach doc).*")) reading = i;
                else if (header.matches(".*(ベトナム|vietnamese|nghia|meaning|意味|dich|translation).*")) vietnamese = i;
                else if (header.matches(".*(例文|example|sentence|vi du).*")) example = i;
            }
        }
        int columnCount = rows.stream().mapToInt(List::size).max().orElse(1);
        int dataStart = hasHeader ? 1 : 0;
        if (japanese < 0) {
            for (int column = 0; column < columnCount; column++) {
                int matches = 0;
                int checked = 0;
                for (int row = dataStart; row < Math.min(rows.size(), dataStart + 20); row++) {
                    String value = column(rows.get(row), column);
                    if (!value.isBlank()) {
                        checked++;
                        if (JAPANESE.matcher(value).find()) matches++;
                    }
                }
                if (checked > 0 && matches * 2 >= checked) {
                    japanese = column;
                    break;
                }
            }
        }
        if (japanese < 0) japanese = 0;
        if (reading < 0) reading = nextJapaneseColumn(rows, dataStart, japanese, columnCount);
        if (vietnamese < 0) vietnamese = nextNonJapaneseColumn(rows, dataStart, japanese, reading, columnCount);
        if (example < 0) example = nextRemainingColumn(columnCount, japanese, reading, vietnamese);
        return Map.of("japanese", japanese, "reading", reading, "vietnamese", vietnamese, "example", example);
    }

    private static int nextJapaneseColumn(List<List<String>> rows, int start, int japanese, int count) {
        for (int column = japanese + 1; column < count; column++) {
            int matches = 0;
            int checked = 0;
            for (int row = start; row < Math.min(rows.size(), start + 10); row++) {
                String value = column(rows.get(row), column);
                if (!value.isBlank()) {
                    checked++;
                    if (JAPANESE.matcher(value).find()) matches++;
                }
            }
            if (checked > 0 && matches * 2 >= checked) return column;
        }
        return -1;
    }

    private static int nextNonJapaneseColumn(List<List<String>> rows, int start, int japanese, int reading, int count) {
        for (int column = 0; column < count; column++) {
            if (column == japanese || column == reading) continue;
            for (int row = start; row < Math.min(rows.size(), start + 10); row++) {
                String value = column(rows.get(row), column);
                if (!value.isBlank() && !JAPANESE.matcher(value).find()) return column;
            }
        }
        return -1;
    }

    private static int nextRemainingColumn(int count, int japanese, int reading, int vietnamese) {
        for (int column = 0; column < count; column++) {
            if (column != japanese && column != reading && column != vietnamese) return column;
        }
        return -1;
    }

    private static boolean looksLikeHeader(List<String> columns, List<List<String>> rows) {
        if (columns.stream().anyMatch(VocabularyImportService::hasKnownHeaderName)) return true;
        if (rows.size() < 2) return false;
        boolean headerHasJapaneseWord = columns.stream().anyMatch(value -> JAPANESE.matcher(value).find());
        boolean sampleHasJapaneseWord = rows.subList(1, Math.min(rows.size(), 6)).stream()
                .flatMap(List::stream)
                .anyMatch(value -> JAPANESE.matcher(value).find());
        return !headerHasJapaneseWord && sampleHasJapaneseWord;
    }

    private static boolean hasKnownHeaderName(String value) {
        String header = normalizeHeader(value);
        return isJapaneseHeader(header)
                || header.matches(".*(ふりがな|hiragana|reading|読み|kana|vietnamese|tieng nhat|nghia|meaning|意味|例文|example|sentence).*");
    }

    private static int japaneseColumnIndex(List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            if (isJapaneseHeader(normalizeHeader(headers.get(i)))) return i;
        }
        return -1;
    }

    private static String normalizeHeader(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isJapaneseHeader(String header) {
        return header.matches(".*(日本語|japanese|単語|語彙|語句|見出し語|kanji|\\bword\\b|\\bterm\\b|vocabulary|tieng nhat|\\bjp\\b|\\bja\\b).*");
    }

    private static String column(List<String> columns, Integer index) {
        return index != null && index >= 0 && index < columns.size() ? columns.get(index) : "";
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    public static String normalizeJapanese(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim();
    }

    private static List<String> parseCsvLine(String line) {
        if (!line.contains(",") && !line.contains("\t") && !line.contains(";") && line.matches(".*\\s{2,}.*")) {
            return List.of(line.trim().split("\\s{2,}", -1));
        }
        List<String> columns = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if ((current == ',' || current == '\t' || current == ';') && !quoted) {
                columns.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        columns.add(value.toString());
        return columns;
    }

    private static final class TableAwarePdfTextStripper extends PDFTextStripper {
        private TableAwarePdfTextStripper() {
            setLineSeparator("\n");
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions.isEmpty()) {
                super.writeString(text);
                return;
            }
            StringBuilder line = new StringBuilder();
            TextPosition previous = null;
            for (TextPosition position : positions) {
                if (previous != null) {
                    float gap = position.getXDirAdj() - previous.getXDirAdj() - previous.getWidthDirAdj();
                    float columnGap = Math.max(9f, previous.getFontSizeInPt() * 1.5f);
                    if (gap > columnGap) {
                        line.append('\t');
                    } else if (gap > 1.5f) {
                        line.append(' ');
                    }
                }
                line.append(position.getUnicode());
                previous = position;
            }
            super.writeString(line.toString());
        }
    }

    private static String column(List<String> columns, int index) {
        return index >= 0 && index < columns.size() ? columns.get(index) : "";
    }
}
