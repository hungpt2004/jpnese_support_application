package com.example.it_jp_interview_server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=render",
        "spring.datasource.url=jdbc:h2:mem:render_session;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureMockMvc
class RenderSessionPersistenceTests {
    private static final String SESSION_COOKIE = "__Host-KOTOBA_SESSION";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void renderSessionIsRestoredFromHttpOnlySecureCookie() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"render-session@example.com","name":"Render learner","password":"safe-password-123"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = registration.getResponse().getHeader("Set-Cookie");
        assertTrue(setCookie != null && setCookie.startsWith(SESSION_COOKIE + "="));
        assertTrue(setCookie.contains("Path=/"));
        assertTrue(setCookie.contains("Secure"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        String cookieValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        mockMvc.perform(get("/api/session").cookie(new jakarta.servlet.http.Cookie(SESSION_COOKIE, cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.name").value("Render learner"));
    }
}
