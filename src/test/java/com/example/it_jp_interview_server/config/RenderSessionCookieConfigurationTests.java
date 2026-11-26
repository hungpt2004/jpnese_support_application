package com.example.it_jp_interview_server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.CookieSerializer.CookieValue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderSessionCookieConfigurationTests {
    @Test
    void renderSessionCookieIsSecureHttpOnlyStrictAndHostScoped() {
        CookieSerializer serializer = new RenderSessionCookieConfiguration().sessionCookieSerializer();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieValue(request, response, "opaque-session-id"));

        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.startsWith("__Host-KOTOBA_SESSION="));
        assertTrue(cookie.contains("Path=/"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertFalse(cookie.contains("Domain="));
    }
}
