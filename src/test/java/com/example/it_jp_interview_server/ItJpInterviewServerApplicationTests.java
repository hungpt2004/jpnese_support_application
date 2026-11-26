package com.example.it_jp_interview_server;

import com.example.it_jp_interview_server.repository.AppUserRepository;
import com.example.it_jp_interview_server.repository.VocabularyBookRepository;
import com.example.it_jp_interview_server.repository.VocabularyEntryRepository;
import com.example.it_jp_interview_server.service.AiAnalysisService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ItJpInterviewServerApplicationTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private VocabularyEntryRepository vocabularyRepository;

    @Autowired
    private VocabularyBookRepository bookRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearData() {
        bookRepository.deleteAll();
        vocabularyRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registrationCreatesAccountAndPrivateVocabulary() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"learner@example.com","name":"Mai","password":"safe-password-123"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode sessionBody = objectMapper.readTree(registration.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) registration.getRequest().getSession(false);
        String csrfToken = sessionBody.path("csrfToken").asString();
        assertNotEquals("safe-password-123", userRepository.findByEmail("learner@example.com").orElseThrow().getPasswordHash());

        mockMvc.perform(post("/api/vocabulary")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"japanese":"要件定義","reading":"ようけんていぎ","vietnamese":"Định nghĩa yêu cầu"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.japanese").value("要件定義"));

        mockMvc.perform(post("/api/vocabulary")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"japanese":"  要件定義  "}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(get("/api/vocabulary").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vietnamese").value("Định nghĩa yêu cầu"));

        MvcResult secondRegistration = register("another-learner@example.com");
        MockHttpSession secondSession = (MockHttpSession) secondRegistration.getRequest().getSession(false);
        mockMvc.perform(get("/api/vocabulary").session(secondSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void protectedActionsRequireAuthenticationAndCsrfToken() throws Exception {
        mockMvc.perform(get("/api/vocabulary"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/vocabulary")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"japanese":"要件定義"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void cohereCompatibilityUsesConfiguredModelAndParsesChatCompletionJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> modelContent = new AtomicReference<>("""
                [{"term":"日本語","reading":"にほんご","hanViet":"Nhật ngữ","onYomi":"ニチ・ジツ — thường gặp trong từ ghép","kunYomi":"ひ — cách đọc kun của 日","exampleJa":"毎日、日本語を勉強しています。","exampleVi":"Tôi học tiếng Nhật mỗi ngày.","jlptContext":"Mondai đọc hiểu/chủ đề học tập; chú ý trạng từ 毎日 chỉ tần suất."}]
                """);
        server.createContext("/compatibility/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "choices", List.of(java.util.Map.of("message", java.util.Map.of("content", modelContent.get())))
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/compatibility/v1";
            AiAnalysisService service = new AiAnalysisService(objectMapper, "test-secret", baseUrl, "command-a-plus-05-2026");
            var analysis = service.analyze(List.of("日本語"));
            assertEquals("にほんご", analysis.get(0).get("reading"));
            assertEquals("ニチ・ジツ — thường gặp trong từ ghép", analysis.get(0).get("onYomi"));
            assertTrue(analysis.get(0).get("jlptContext").contains("Mondai"));
            assertEquals("Bearer test-secret", authorization.get());
            JsonNode sent = objectMapper.readTree(requestBody.get());
            assertEquals("command-a-plus-05-2026", sent.path("model").asString());
            assertTrue(sent.path("messages").isArray());
            assertEquals("system", sent.path("messages").get(0).path("role").asString());
            assertTrue(sent.path("messages").get(1).path("content").asString().contains("日本語"));
            modelContent.set("""
                    ```json
                    {"term":"仕事","reading":"しごと","hanViet":"Sĩ sự","onYomi":"—","kunYomi":"—","exampleJa":"仕事を探しています。","exampleVi":"Tôi đang tìm việc.","jlptContext":"Mondai hội thoại công việc."}
                    ```
                    """);
            var singleWordAnalysis = service.analyze(List.of("仕事 — しごと — công việc"));
            assertEquals(1, singleWordAnalysis.size());
            assertEquals("仕事", singleWordAnalysis.get(0).get("term"));
            modelContent.set("""
                    {"items":[{"exampleJa":"仕事を探しています。","exampleVi":"Tôi đang tìm việc.","jlptContext":"Mondai hội thoại công việc."}]}
                    """);
            var wrappedAnalysis = service.analyze(List.of("仕事 — しごと — công việc"));
            assertEquals("仕事", wrappedAnalysis.get(0).get("term"));
            assertEquals("しごと", wrappedAnalysis.get(0).get("reading"));
            modelContent.set(objectMapper.writeValueAsString(java.util.Map.of(
                    "answerJapanese", "経験を活かして課題を解決しました。",
                    "answerVietnamese", "Tôi đã giải quyết vấn đề bằng kinh nghiệm của mình.",
                    "reason", "Nêu động lực từ trải nghiệm.",
                    "handling", "Giải thích hành động cụ thể.",
                    "story", "Dùng câu chuyện đã được cung cấp."
            )));
            var answer = service.generateInterviewAnswer(
                    "課題をどう解決しましたか。",
                    "開発",
                    "Tôi xử lý lỗi đăng nhập bằng cách xem log, sửa validation và kiểm thử lại."
            );
            assertEquals("経験を活かして課題を解決しました。", answer.get("answerJapanese"));
            JsonNode answerRequest = objectMapper.readTree(requestBody.get());
            assertTrue(answerRequest.path("messages").get(1).path("content").asString().contains("Tôi xử lý lỗi đăng nhập"));

            modelContent.set(objectMapper.writeValueAsString(java.util.Map.of(
                    "japanese", 2, "reading", 1, "vietnamese", 0, "example", 3
            )));
            var columnMapping = service.mapColumns(
                    List.of("Meaning", "Kana", "Japanese term", "Sample sentence"),
                    List.of(List.of("Định nghĩa", "てすと", "テスト", "テストです。"))
            );
            assertEquals(2, columnMapping.get("japanese"));
            assertEquals(1, columnMapping.get("reading"));
            assertEquals(0, columnMapping.get("vietnamese"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void importFindsJapaneseAndVietnameseColumnsByHeader() throws Exception {
        MvcResult registration = register("file-learner@example.com");
        JsonNode sessionBody = objectMapper.readTree(registration.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) registration.getRequest().getSession(false);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "words.csv",
                "text/csv",
                "Vietnamese,Example,Hiragana,Japanese\nĐịnh nghĩa yêu cầu,要件を定義します。,ようけんていぎ,要件定義\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/vocabulary/import")
                        .file(file)
                        .param("setName", "Cloud fundamentals")
                        .param("useAi", "false")
                        .session(session)
                        .header("X-CSRF-TOKEN", sessionBody.path("csrfToken").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.entries[0].japanese").value("要件定義"))
                .andExpect(jsonPath("$.entries[0].setName").value("Cloud fundamentals"))
                .andExpect(jsonPath("$.entries[0].vietnamese").value("Định nghĩa yêu cầu"));
    }

    @Test
    void importSkipsExistingAndRepeatedJapaneseWords() throws Exception {
        MvcResult registration = register("duplicate-file-learner@example.com");
        JsonNode sessionBody = objectMapper.readTree(registration.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) registration.getRequest().getSession(false);
        String csrfToken = sessionBody.path("csrfToken").asString();
        mockMvc.perform(post("/api/vocabulary")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"japanese":"要件定義"}
                                """))
                .andExpect(status().isOk());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "duplicates.csv",
                "text/csv",
                "Japanese,Hiragana,Vietnamese\n要件定義,ようけんていぎ,đã có\nテスト,てすと,kiểm thử\n テスト ,てすと,kiểm thử\n".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/vocabulary/import")
                        .file(file)
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.duplicatesSkipped").value(2))
                .andExpect(jsonPath("$.entries[0].japanese").value("テスト"));
    }

    @Test
    void publicVocabularyBooksCanBeSearchedAndPrivateBooksStayHidden() throws Exception {
        MvcResult registration = register("books@example.com");
        JsonNode sessionBody = objectMapper.readTree(registration.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) registration.getRequest().getSession(false);
        String csrfToken = sessionBody.path("csrfToken").asString();

        MvcResult created = mockMvc.perform(post("/api/vocabulary/books")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"JLPT N3"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublic").value(false))
                .andReturn();
        long bookId = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();

        mockMvc.perform(post("/api/vocabulary")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"setName":"JLPT N3","japanese":"改善","reading":"かいぜん","vietnamese":"cải thiện"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/vocabulary/books/" + bookId + "/visibility")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"isPublic":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublic").value(true))
                .andExpect(jsonPath("$.entryCount").value(1));

        mockMvc.perform(get("/api/community/books").param("q", "JLPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("JLPT N3"));
        mockMvc.perform(get("/api/community/books/" + bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].japanese").value("改善"));

        MvcResult learnerRegistration = register("learner-books@example.com");
        JsonNode learnerSessionBody = objectMapper.readTree(learnerRegistration.getResponse().getContentAsString());
        MockHttpSession learnerSession = (MockHttpSession) learnerRegistration.getRequest().getSession(false);
        String learnerCsrf = learnerSessionBody.path("csrfToken").asString();
        mockMvc.perform(post("/api/community/books/" + bookId + "/save")
                        .session(learnerSession)
                        .header("X-CSRF-TOKEN", learnerCsrf)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copiedCount").value(1))
                .andExpect(jsonPath("$.book.name").value("JLPT N3 (bản sao)"));
        mockMvc.perform(get("/api/vocabulary").session(learnerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].japanese").value("改善"))
                .andExpect(jsonPath("$[0].setName").value("JLPT N3 (bản sao)"));
        mockMvc.perform(get("/api/vocabulary/books").session(learnerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("JLPT N3 (bản sao)"));

        mockMvc.perform(post("/api/vocabulary/books/" + bookId + "/visibility")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"isPublic":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublic").value(false));
        mockMvc.perform(get("/api/community/books/" + bookId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/community/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private MvcResult register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email, "name", "Test", "password", "safe-password-123"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
    }
}
