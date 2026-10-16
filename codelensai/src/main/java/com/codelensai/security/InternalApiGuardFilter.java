package com.codelensai.security;

import com.codelensai.config.InternalApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the eval-only {@code /api/internal/**} surface: 404 unless {@code codelens.internal.enabled=true}
 * (it spends LLM tokens without a user session), and 401 unless {@code X-Internal-Token} matches
 * {@code codelens.internal.token} when one is configured (constant-time comparison).
 */
@Component
public class InternalApiGuardFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Token";

    private final InternalApiProperties props;

    public InternalApiGuardFilter(InternalApiProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!Boolean.TRUE.equals(props.enabled())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String expected = props.token();
        if (expected != null && !expected.isBlank()) {
            String given = request.getHeader(HEADER);
            if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
