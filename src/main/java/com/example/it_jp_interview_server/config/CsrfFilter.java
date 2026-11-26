package com.example.it_jp_interview_server.config;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Component
public class CsrfFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String CSRF_TOKEN = "csrfToken";
    private final ObjectMapper objectMapper;

    public CsrfFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean authEntry = path.equals("/api/auth/login") || path.equals("/api/auth/register");
        if (path.startsWith("/api/") && !SAFE_METHODS.contains(request.getMethod()) && !authEntry) {
            HttpSession session = request.getSession(false);
            String expected = session == null ? null : (String) session.getAttribute(CSRF_TOKEN);
            String supplied = request.getHeader("X-CSRF-TOKEN");
            if (expected == null || supplied == null || !java.security.MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                objectMapper.writeValue(response.getWriter(), Map.of("error", "Phiên làm việc không hợp lệ. Hãy tải lại trang."));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
