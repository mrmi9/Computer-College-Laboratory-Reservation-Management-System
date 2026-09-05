package com.college.labbooking.security;

import com.college.labbooking.common.api.ApiEnvelope;
import com.college.labbooking.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 20_000;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long now = clock.millis();
        boolean login = "/api/v1/auth/login".equals(request.getRequestURI());
        int limit = login ? properties.loginMaxPerMinute() : properties.maxPerMinute();
        String prefix = login ? "login:" : "api:";
        String key = prefix + clientKey(request);
        if (!counters.containsKey(key) && counters.size() >= MAX_TRACKED_CLIENTS) {
            counters.entrySet().removeIf(entry -> entry.getValue().expired(now));
            if (counters.size() >= MAX_TRACKED_CLIENTS) {
                key = prefix + "overflow:" + request.getRemoteAddr();
            }
        }
        WindowCounter counter = counters.computeIfAbsent(key, ignored -> new WindowCounter(now));
        if (!counter.allow(now, limit)) {
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(
                    response.getOutputStream(),
                    ApiEnvelope.error("RATE_LIMIT_EXCEEDED", "请求过于频繁，请稍后重试", null));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return digest(authorization.substring(7));
        }
        return request.getRemoteAddr();
    }

    private String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class WindowCounter {
        private long startedAt;
        private int count;

        private WindowCounter(long now) {
            this.startedAt = now;
        }

        private synchronized boolean allow(long now, int limit) {
            if (now - startedAt >= WINDOW_MILLIS) {
                startedAt = now;
                count = 0;
            }
            count += 1;
            return count <= limit;
        }

        private synchronized boolean expired(long now) {
            return now - startedAt >= WINDOW_MILLIS;
        }
    }
}
