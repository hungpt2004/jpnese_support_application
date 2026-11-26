package com.example.it_jp_interview_server.controller;

import com.example.it_jp_interview_server.model.AppUser;
import com.example.it_jp_interview_server.model.VocabularyBook;
import com.example.it_jp_interview_server.model.VocabularyEntry;
import com.example.it_jp_interview_server.repository.AppUserRepository;
import com.example.it_jp_interview_server.repository.VocabularyBookRepository;
import com.example.it_jp_interview_server.repository.VocabularyEntryRepository;
import com.example.it_jp_interview_server.service.AiAnalysisService;
import com.example.it_jp_interview_server.service.VocabularyImportService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
public class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    private static final String USER_ID = "userId";
    private static final String CSRF_TOKEN = "csrfToken";
    private final AppUserRepository userRepository;
    private final VocabularyBookRepository bookRepository;
    private final VocabularyEntryRepository vocabularyRepository;
    private final VocabularyImportService importService;
    private final AiAnalysisService aiAnalysisService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final HttpClient dictionaryHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ApiController(
            AppUserRepository userRepository,
            VocabularyBookRepository bookRepository,
            VocabularyEntryRepository vocabularyRepository,
            VocabularyImportService importService,
            AiAnalysisService aiAnalysisService,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper
    ) {
        this.userRepository = userRepository;
        this.bookRepository = bookRepository;
        this.vocabularyRepository = vocabularyRepository;
        this.importService = importService;
        this.aiAnalysisService = aiAnalysisService;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/session")
    public Map<String, Object> session(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        AppUser user = currentUser(session);
        return Map.of(
                "authenticated", user != null,
                "name", user == null ? "" : user.getDisplayName(),
                "csrfToken", csrf(session)
        );
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (body == null) {
            return error(HttpStatus.BAD_REQUEST, "Thông tin đăng ký không hợp lệ.");
        }
        String email = value(body, "email").trim().toLowerCase(Locale.ROOT);
        String name = value(body, "name").trim();
        String password = value(body, "password");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.length() > 190) {
            return error(HttpStatus.BAD_REQUEST, "Email không hợp lệ.");
        }
        if (name.isBlank() || name.length() > 80 || password.length() < 10
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return error(HttpStatus.BAD_REQUEST, "Tên cần từ 1–80 ký tự, mật khẩu tối thiểu 10 ký tự và tối đa 72 byte UTF-8.");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            return error(HttpStatus.CONFLICT, "Email này đã được đăng ký.");
        }
        try {
            AppUser user = userRepository.save(new AppUser(email, name, passwordEncoder.encode(password)));
            return ResponseEntity.ok(loginSession(user, request));
        } catch (DataIntegrityViolationException ex) {
            return error(HttpStatus.CONFLICT, "Email này đã được đăng ký.");
        }
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (body == null) {
            return error(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu chưa chính xác.");
        }
        String email = value(body, "email").trim().toLowerCase(Locale.ROOT);
        String password = value(body, "password");
        if (email.length() > 190 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return error(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu chưa chính xác.");
        }
        AppUser user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return error(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu chưa chính xác.");
        }
        return ResponseEntity.ok(loginSession(user, request));
    }

    @PostMapping("/api/auth/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Map.of("message", "Đã đăng xuất.");
    }

    @GetMapping("/api/dashboard")
    public Map<String, Object> dashboard(HttpServletRequest request) {
        AppUser user = requireUser(request);
        return Map.of(
                "name", user.getDisplayName(),
                "vocabularyCount", vocabularyRepository.countByUserId(user.getId()),
                "questions", questions().size()
        );
    }

    @GetMapping("/api/vocabulary")
    public List<VocabularyEntry> vocabulary(HttpServletRequest request) {
        AppUser user = requireUser(request);
        synchronizeLegacyBooks(user.getId());
        return vocabularyRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @GetMapping("/api/vocabulary/books")
    public List<Map<String, Object>> vocabularyBooks(HttpServletRequest request) {
        AppUser user = requireUser(request);
        synchronizeLegacyBooks(user.getId());
        return bookRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(book -> bookSummary(book, user.getDisplayName(),
                        vocabularyRepository.countByUserIdAndSetName(user.getId(), book.getName())))
                .toList();
    }

    @PostMapping("/api/vocabulary/books")
    public ResponseEntity<?> createVocabularyBook(@RequestBody Map<String, String> body, HttpServletRequest request) {
        AppUser user = requireUser(request);
        String name = limit(value(body, "name"), 100);
        if (name.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Tên sổ từ không được để trống.");
        }
        if (bookRepository.findByUserIdAndNameIgnoreCase(user.getId(), name).isPresent()) {
            return error(HttpStatus.CONFLICT, "Bạn đã có sổ từ cùng tên.");
        }
        VocabularyBook book = new VocabularyBook(user.getId(), name);
        book.setDescription(limit(value(body, "description"), 500));
        book = bookRepository.save(book);
        return ResponseEntity.ok(bookSummary(book, user.getDisplayName(), 0));
    }

    @PostMapping("/api/vocabulary/books/{id}/visibility")
    public ResponseEntity<?> updateVocabularyBookVisibility(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        AppUser user = requireUser(request);
        VocabularyBook book = bookRepository.findByIdAndUserId(id, user.getId()).orElse(null);
        if (book == null) {
            return error(HttpStatus.NOT_FOUND, "Không tìm thấy sổ từ.");
        }
        Object publicValue = body.get("isPublic");
        if (!(publicValue instanceof Boolean isPublic)) {
            return error(HttpStatus.BAD_REQUEST, "Trạng thái công khai không hợp lệ.");
        }
        book.setPublic(isPublic);
        book = bookRepository.save(book);
        return ResponseEntity.ok(bookSummary(book, user.getDisplayName(),
                vocabularyRepository.countByUserIdAndSetName(user.getId(), book.getName())));
    }

    @GetMapping("/api/community/books")
    public List<Map<String, Object>> communityBooks(@RequestParam(defaultValue = "") String q) {
        String query = q.trim().toLowerCase(Locale.ROOT);
        List<VocabularyBook> books = bookRepository.findTop50ByIsPublicTrueOrderByCreatedAtDesc();
        Map<Long, String> authorNames = new LinkedHashMap<>();
        userRepository.findAllById(books.stream().map(VocabularyBook::getUserId).distinct().toList())
                .forEach(user -> authorNames.put(user.getId(), user.getDisplayName()));
        return books.stream()
                .filter(book -> {
                    String author = authorNames.getOrDefault(book.getUserId(), "Người học Kotoba");
                    return query.isBlank() || book.getName().toLowerCase(Locale.ROOT).contains(query)
                            || book.getDescription().toLowerCase(Locale.ROOT).contains(query)
                            || author.toLowerCase(Locale.ROOT).contains(query);
                })
                .map(book -> bookSummary(book, authorNames.getOrDefault(book.getUserId(), "Người học Kotoba"),
                        vocabularyRepository.countByUserIdAndSetName(book.getUserId(), book.getName())))
                .toList();
    }

    @GetMapping("/api/community/books/{id}")
    public ResponseEntity<?> communityBook(@PathVariable Long id) {
        VocabularyBook book = bookRepository.findById(id).filter(VocabularyBook::isPublic).orElse(null);
        if (book == null) {
            return error(HttpStatus.NOT_FOUND, "Sổ từ này không tồn tại hoặc không được chia sẻ công khai.");
        }
        String author = userRepository.findById(book.getUserId())
                .map(AppUser::getDisplayName).orElse("Người học Kotoba");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("book", bookSummary(book, author,
                vocabularyRepository.countByUserIdAndSetName(book.getUserId(), book.getName())));
        result.put("entries", vocabularyRepository.findAllByUserIdAndSetNameOrderByCreatedAtDesc(
                book.getUserId(), book.getName()));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/community/books/{id}/save")
    @Transactional
    public ResponseEntity<?> saveCommunityBook(@PathVariable Long id, HttpServletRequest request) {
        AppUser user = requireUser(request);
        VocabularyBook sourceBook = bookRepository.findById(id).filter(VocabularyBook::isPublic).orElse(null);
        if (sourceBook == null) {
            return error(HttpStatus.NOT_FOUND, "Sổ từ này không tồn tại hoặc không được chia sẻ công khai.");
        }

        String baseName = limit(sourceBook.getName() + " (bản sao)", 100);
        String copyName = baseName;
        int suffix = 2;
        while (bookRepository.findByUserIdAndNameIgnoreCase(user.getId(), copyName).isPresent()) {
            String ending = " (bản sao " + suffix++ + ")";
            copyName = limit(sourceBook.getName(), 100 - ending.length()) + ending;
        }

        AppUser sourceOwner = userRepository.findById(sourceBook.getUserId()).orElse(null);
        String sourceOwnerName = sourceOwner == null ? "người học Kotoba" : sourceOwner.getDisplayName();
        VocabularyBook savedBook = new VocabularyBook(user.getId(), copyName);
        savedBook.setDescription(limit("Sao chép từ sổ công khai của " + sourceOwnerName, 500));
        savedBook = bookRepository.save(savedBook);

        List<VocabularyEntry> sourceEntries = vocabularyRepository.findAllByUserIdAndSetNameOrderByCreatedAtDesc(
                sourceBook.getUserId(), sourceBook.getName());
        int copiedCount = 0;
        for (VocabularyEntry entry : sourceEntries) {
            if (vocabularyRepository.existsByUserIdAndJapaneseIgnoreCase(user.getId(), entry.getJapanese())) {
                continue;
            }
            vocabularyRepository.save(new VocabularyEntry(
                    user.getId(), entry.getJapanese(), copyName, entry.getReading(), entry.getVietnamese(), entry.getExample()));
            copiedCount++;
        }
        return ResponseEntity.ok(Map.of(
                "book", bookSummary(savedBook, user.getDisplayName(), copiedCount),
                "copiedCount", copiedCount,
                "duplicatesSkipped", sourceEntries.size() - copiedCount
        ));
    }

    @PostMapping("/api/vocabulary")
    public ResponseEntity<?> addVocabulary(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long userId = requireUser(request).getId();
        if (body == null) {
            return error(HttpStatus.BAD_REQUEST, "Dữ liệu từ vựng không hợp lệ.");
        }
        String japanese = VocabularyImportService.normalizeJapanese(value(body, "japanese"));
        if (japanese.isBlank() || japanese.length() > 200) {
            return error(HttpStatus.BAD_REQUEST, "Từ tiếng Nhật không được để trống và tối đa 200 ký tự.");
        }
        if (vocabularyRepository.existsByUserIdAndJapaneseIgnoreCase(userId, japanese)) {
            return error(HttpStatus.CONFLICT, "Từ này đã có trong sổ từ của bạn.");
        }
        String setName = limit(value(body, "setName").isBlank() ? "Từ vựng của tôi" : value(body, "setName"), 100);
        ensureBook(userId, setName);
        VocabularyEntry entry = vocabularyRepository.save(new VocabularyEntry(
                userId, japanese, setName,
                limit(value(body, "reading"), 200), limit(value(body, "vietnamese"), 500),
                limit(value(body, "example"), 600)
        ));
        return ResponseEntity.ok(entry);
    }

    @DeleteMapping("/api/vocabulary/{id}")
    public ResponseEntity<?> deleteVocabulary(@PathVariable Long id, HttpServletRequest request) {
        VocabularyEntry entry = vocabularyRepository.findByIdAndUserId(id, requireUser(request).getId()).orElse(null);
        if (entry == null) {
            return error(HttpStatus.NOT_FOUND, "Không tìm thấy từ vựng.");
        }
        vocabularyRepository.delete(entry);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/vocabulary/import")
    public ResponseEntity<?> importVocabulary(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "") String setName,
            @RequestParam(defaultValue = "false") boolean useAi,
            HttpServletRequest request
    ) {
        try {
            Long userId = requireUser(request).getId();
            log.info("Vocabulary import started: userId={}, sizeBytes={}, useAi={}", userId, file.getSize(), useAi);
            VocabularyImportService.ImportResult imported = importService.importFile(userId, file, setName, useAi);
            imported.entries().stream().map(VocabularyEntry::getSetName).distinct()
                    .forEach(name -> ensureBook(userId, name));
            log.info("Vocabulary import completed: userId={}, imported={}, duplicatesSkipped={}",
                    userId, imported.entries().size(), imported.duplicatesSkipped());
            return ResponseEntity.ok(Map.of(
                    "imported", imported.entries().size(),
                    "duplicatesSkipped", imported.duplicatesSkipped(),
                    "entries", imported.entries()
            ));
        } catch (IllegalArgumentException ex) {
            log.warn("Vocabulary import rejected: {}", ex.getMessage());
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            log.warn("Vocabulary import unavailable: {}", ex.getMessage());
            return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        } catch (java.io.IOException ex) {
            log.warn("Unable to read vocabulary import file", ex);
            return error(HttpStatus.BAD_REQUEST, "Không thể đọc file. Hãy kiểm tra định dạng và nội dung file.");
        } catch (Exception ex) {
            log.error("Unexpected error while importing vocabulary", ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể nhập file do lỗi máy chủ. Hãy kiểm tra log backend.");
        }
    }

    @PostMapping("/api/ai/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, List<String>> body, HttpServletRequest request) {
        requireUser(request);
        if (body == null) {
            return error(HttpStatus.BAD_REQUEST, "Dữ liệu từ vựng không hợp lệ.");
        }
        try {
            return ResponseEntity.ok(Map.of("items", aiAnalysisService.analyze(body.get("terms"))));
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        } catch (Exception ex) {
            log.error("AI vocabulary analysis failed", ex);
            return error(HttpStatus.BAD_GATEWAY, "Không thể kết nối dịch vụ AI. Vui lòng thử lại sau.");
        }
    }

    @PostMapping("/api/interview/answer")
    public ResponseEntity<?> generateInterviewAnswer(@RequestBody Map<String, String> body, HttpServletRequest request) {
        requireUser(request);
        if (body == null) {
            return error(HttpStatus.BAD_REQUEST, "Thông tin câu hỏi và kinh nghiệm không hợp lệ.");
        }
        String question = value(body, "question").trim();
        String category = value(body, "category").trim();
        String experience = value(body, "experience").trim();
        if (question.isBlank() || question.length() > 500 || category.length() > 80) {
            return error(HttpStatus.BAD_REQUEST, "Câu hỏi hoặc chuyên ngành không hợp lệ.");
        }
        if (experience.length() < 30 || experience.length() > 2500) {
            return error(HttpStatus.BAD_REQUEST, "Hãy mô tả kinh nghiệm thực tế từ 30 đến 2.500 ký tự để AI viết câu trả lời phù hợp.");
        }
        try {
            return ResponseEntity.ok(aiAnalysisService.generateInterviewAnswer(question, category, experience));
        } catch (IllegalStateException ex) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        } catch (Exception ex) {
            log.error("AI interview answer generation failed", ex);
            return error(HttpStatus.BAD_GATEWAY, "AI chưa tạo được câu trả lời. Vui lòng thử lại.");
        }
    }

    @GetMapping("/api/dictionary")
    public ResponseEntity<?> dictionary(
            @RequestParam("word") String word,
            @RequestParam(name = "quick", defaultValue = "false") boolean quick,
            HttpServletRequest request) {
        if (!quick) requireUser(request);
        if (word.isBlank() || word.length() > 100) {
            return error(HttpStatus.BAD_REQUEST, "Từ cần tra cứu không hợp lệ.");
        }
        try {
            String encoded = java.net.URLEncoder.encode(word, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create("https://jisho.org/api/v1/search/words?keyword=" + encoded))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = dictionaryHttpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return error(HttpStatus.BAD_GATEWAY, "Từ điển hiện không phản hồi.");
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return ResponseEntity.ok(Map.of("found", false, "message", "Chưa tìm thấy từ này trong từ điển."));
            }
            JsonNode first = bestDictionaryMatch(data, word);
            JsonNode senses = first.path("senses");
            List<String> definitions = new java.util.ArrayList<>();
            List<String> partsOfSpeech = new java.util.ArrayList<>();
            if (senses.isArray()) {
                for (JsonNode sense : senses) {
                    JsonNode glosses = sense.path("english_definitions");
                    if (glosses.isArray()) {
                        for (JsonNode gloss : glosses) {
                            if (gloss.isString() && definitions.size() < 12) definitions.add(gloss.asString());
                        }
                    }
                    JsonNode speech = sense.path("parts_of_speech");
                    if (speech.isArray()) {
                        for (JsonNode part : speech) {
                            if (part.isString() && !partsOfSpeech.contains(part.asString())) partsOfSpeech.add(part.asString());
                        }
                    }
                }
            }
            String meaning = String.join("; ", definitions);
            String reading = first.path("japanese").path(0).path("reading").asString("");
            String kanji = first.path("japanese").path(0).path("word").asString(word);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("found", true);
            result.put("word", kanji);
            result.put("reading", reading);
            result.put("meaning", meaning);
            result.put("definitions", definitions);
            result.put("partsOfSpeech", partsOfSpeech);
            result.put("jlpt", first.path("jlpt").isArray() ? objectMapper.convertValue(first.path("jlpt"), List.class) : List.of());
            result.put("isCommon", first.path("is_common").asBoolean(false));
            result.put("source", "Jisho.org / JMdict");
            result.put("sourceUrl", "https://jisho.org/search/" + encoded);
            result.put("attribution", "Từ điển Jisho sử dụng dữ liệu JMdict (Electronic Dictionary Research and Development Group).");
            if (!quick && aiAnalysisService.isConfigured()) {
                try {
                    result.putAll(aiAnalysisService.enrichDictionary(kanji, reading, meaning));
                } catch (Exception ex) {
                    result.put("translationNote", "Chưa dịch được sang tiếng Việt; nghĩa tiếng Anh vẫn được hiển thị.");
                }
            } else {
                result.put("translationNote", "Thiết lập AI_API_KEY để xem nghĩa tiếng Việt, âm Hán Việt và câu ví dụ.");
            }
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            return error(HttpStatus.BAD_GATEWAY, "Không thể kết nối từ điển Nhật. Vui lòng thử lại.");
        }
    }

    private static JsonNode bestDictionaryMatch(JsonNode entries, String query) {
        for (JsonNode entry : entries) {
            JsonNode spellings = entry.path("japanese");
            if (!spellings.isArray()) continue;
            for (JsonNode spelling : spellings) {
                if (query.equals(spelling.path("word").asString(""))
                        || query.equals(spelling.path("reading").asString(""))) {
                    return entry;
                }
            }
        }
        return entries.get(0);
    }

    @GetMapping("/api/questions")
    public List<Map<String, String>> questions(@RequestParam(required = false) String category) {
        List<Map<String, String>> all = questions();
        if (category == null || category.isBlank() || category.equalsIgnoreCase("all")) {
            return all;
        }
        return all.stream().filter(question -> question.get("category").equalsIgnoreCase(category)).toList();
    }

    private List<Map<String, String>> questions() {
        return List.of(
                question("IT 基礎", "これまでに使ったプログラミング言語と、その経験について教えてください。", "Hãy cho biết ngôn ngữ lập trình bạn đã sử dụng và kinh nghiệm với chúng."),
                question("IT 基礎", "チームで開発するときに大切にしていることは何ですか。", "Điều gì quan trọng với bạn khi phát triển phần mềm theo nhóm?"),
                question("IT 基礎", "これまでのプロジェクトで直面した課題と、その解決方法を教えてください。", "Hãy kể về một vấn đề trong dự án và cách bạn giải quyết."),
                question("開発", "オブジェクト指向プログラミングについて説明してください。", "Hãy giải thích lập trình hướng đối tượng."),
                question("開発", "コードレビューで指摘を受けたとき、どのように対応しますか。", "Bạn phản hồi thế nào khi nhận góp ý trong code review?"),
                question("開発", "単体テストと結合テストの違いを説明してください。", "Hãy giải thích khác nhau giữa kiểm thử đơn vị và kiểm thử tích hợp."),
                question("開発", "障害が発生したとき、原因をどのように調査しますか。", "Bạn điều tra nguyên nhân như thế nào khi xảy ra sự cố?"),
                question("クラウド", "AWSやAzureなどのクラウドサービスを使った経験を教えてください。", "Hãy chia sẻ kinh nghiệm sử dụng AWS, Azure hoặc dịch vụ cloud."),
                question("クラウド", "可用性とスケーラビリティの違いを説明してください。", "Hãy phân biệt tính sẵn sàng cao và khả năng mở rộng."),
                question("クラウド", "Dockerを使うメリットは何だと思いますか。", "Theo bạn, lợi ích của Docker là gì?"),
                question("インフラ", "サーバーのCPU使用率が急上昇した場合、どう対応しますか。", "Bạn làm gì khi mức sử dụng CPU của server tăng đột biến?"),
                question("インフラ", "データベースのバックアップと復旧について説明してください。", "Hãy giải thích việc sao lưu và khôi phục cơ sở dữ liệu."),
                question("ヘルプデスク", "ユーザーから曖昧な問い合わせを受けたとき、どう対応しますか。", "Bạn xử lý thế nào khi người dùng gửi yêu cầu chưa rõ ràng?"),
                question("ヘルプデスク", "技術に詳しくない方に問題の原因をどう説明しますか。", "Bạn giải thích nguyên nhân cho người không chuyên kỹ thuật như thế nào?"),
                question("ヘルプデスク", "複数の問い合わせが同時に来た場合、どのように優先順位をつけますか。", "Bạn sắp xếp ưu tiên ra sao khi có nhiều yêu cầu cùng lúc?"),
                question("プロジェクト管理", "納期が遅れそうな場合、どのように報告・調整しますか。", "Bạn báo cáo và điều chỉnh thế nào khi có nguy cơ trễ hạn?"),
                question("プロジェクト管理", "意見が対立したとき、どのように合意形成しますか。", "Bạn tìm tiếng nói chung như thế nào khi có bất đồng?"),
                question("プロジェクト管理", "見積もりと実績に差が出た場合、どう改善しますか。", "Bạn cải thiện gì khi thời gian ước tính và thực tế khác nhau?"),
                question("日本で働く", "日本で働きたい理由を教えてください。", "Hãy cho biết lý do bạn muốn làm việc tại Nhật."),
                question("日本で働く", "日本語での報告・連絡・相談をどのように実践していますか。", "Bạn thực hiện báo cáo, liên lạc, trao đổi bằng tiếng Nhật như thế nào?"),
                question("日本で働く", "日本の職場文化について、どのように理解していますか。", "Bạn hiểu thế nào về văn hóa nơi làm việc ở Nhật?"),
                question("日本で働く", "入社後、どのようなエンジニアになりたいですか。", "Sau khi vào công ty, bạn muốn trở thành kỹ sư như thế nào?")
        );
    }

    private Map<String, String> question(String category, String japanese, String vietnamese) {
        return Map.of(
                "category", category,
                "japanese", japanese,
                "vietnamese", vietnamese,
                "tip", coachingTip(category)
        );
    }

    private String coachingTip(String category) {
        return switch (category) {
            case "開発" ->
                    "Nêu rõ phần việc của chính bạn, cách kiểm chứng kết quả và điều bạn học được. Khi nói về code review, thể hiện thái độ tiếp nhận góp ý và cùng nâng chất lượng sản phẩm.";
            case "クラウド", "インフラ" ->
                    "Trình bày cách kiểm tra theo thứ tự: phạm vi ảnh hưởng → log/metrics → giả thuyết → biện pháp an toàn. Đừng quên báo cáo tiến độ và phương án khôi phục.";
            case "ヘルプデスク" ->
                    "Cho thấy bạn biết lắng nghe, xác nhận lại vấn đề bằng lời dễ hiểu, ưu tiên theo mức độ ảnh hưởng và cập nhật cho người dùng đến khi xử lý xong.";
            case "プロジェクト管理" ->
                    "Nói về việc chia sẻ rủi ro sớm, đưa ra dữ kiện và phương án thay vì chỉ báo vấn đề. Nhấn mạnh phối hợp và thống nhất kỳ vọng với các bên.";
            case "日本で働く" ->
                    "Trả lời chân thật, cụ thể và khi phù hợp hãy nhắc đến 報連相 (báo cáo–liên lạc–trao đổi), tinh thần làm việc nhóm và mong muốn đóng góp lâu dài.";
            default ->
                    "Bắt đầu bằng kết luận ngắn gọn, sau đó đưa một ví dụ thật theo trình tự tình huống → hành động của bạn → kết quả. Nêu điều bạn học được, không phóng đại hay đổ lỗi.";
        };
    }

    private Map<String, Object> loginSession(AppUser user, HttpServletRequest request) {
        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(USER_ID, user.getId());
        String token = csrf(session);
        return Map.of("authenticated", true, "name", user.getDisplayName(), "csrfToken", token);
    }

    private void synchronizeLegacyBooks(Long userId) {
        vocabularyRepository.findDistinctSetNamesByUserId(userId).stream()
                .filter(name -> name != null && !name.isBlank())
                .forEach(name -> ensureBook(userId, name));
    }

    private void ensureBook(Long userId, String name) {
        String normalizedName = limit(name == null || name.isBlank() ? "Từ vựng của tôi" : name, 100);
        bookRepository.findByUserIdAndNameIgnoreCase(userId, normalizedName)
                .orElseGet(() -> bookRepository.save(new VocabularyBook(userId, normalizedName)));
    }

    private Map<String, Object> bookSummary(VocabularyBook book, String ownerName, long entryCount) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", book.getId());
        summary.put("name", book.getName());
        summary.put("description", book.getDescription());
        summary.put("isPublic", book.isPublic());
        summary.put("entryCount", entryCount);
        summary.put("ownerName", ownerName);
        summary.put("createdAt", book.getCreatedAt());
        return summary;
    }

    private String csrf(HttpSession session) {
        String token = (String) session.getAttribute(CSRF_TOKEN);
        if (token == null) {
            token = UUID.randomUUID().toString();
            session.setAttribute(CSRF_TOKEN, token);
        }
        return token;
    }

    private AppUser requireUser(HttpServletRequest request) {
        AppUser user = currentUser(request.getSession(false));
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để tiếp tục.");
        }
        return user;
    }

    private AppUser currentUser(HttpSession session) {
        if (session == null || !(session.getAttribute(USER_ID) instanceof Long userId)) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String value(Map<String, String> body, String key) {
        return body.get(key) == null ? "" : body.get(key);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApiException(ApiException exception) {
        return error(exception.status, exception.getMessage());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleInvalidJson() {
        return error(HttpStatus.BAD_REQUEST, "Dữ liệu gửi lên không hợp lệ.");
    }

    private static class ApiException extends RuntimeException {
        private final HttpStatus status;

        private ApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }
}
