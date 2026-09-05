package com.college.labbooking.auth.web;

import com.college.labbooking.auth.application.AuthService;
import com.college.labbooking.auth.application.SecurityProperties;
import com.college.labbooking.auth.application.TokenDigests;
import com.college.labbooking.common.api.ApiEnvelope;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.security.CurrentUser;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public static final String REFRESH_COOKIE = "lab_refresh";
    public static final String CSRF_COOKIE = "lab_csrf";

    private final AuthService authService;
    private final SecurityProperties properties;
    private final TokenDigests tokenDigests;

    public AuthController(AuthService authService, SecurityProperties properties, TokenDigests tokenDigests) {
        this.authService = authService;
        this.properties = properties;
        this.tokenDigests = tokenDigests;
    }

    @PostMapping("/login")
    @SecurityRequirements
    public ResponseEntity<ApiEnvelope<SessionView>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        AuthService.IssuedSession issued = authService.login(
                request.username().trim(), request.password(), servletRequest.getHeader(HttpHeaders.USER_AGENT));
        return sessionResponse(issued);
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public ResponseEntity<ApiEnvelope<SessionView>> refresh(
            HttpServletRequest request,
            @RequestHeader(value = "X-CSRF-TOKEN", required = false) String csrfHeader) {
        String refreshToken = cookie(request, REFRESH_COOKIE);
        String csrfCookie = cookie(request, CSRF_COOKIE);
        if (refreshToken == null || !tokenDigests.constantTimeEquals(csrfHeader, csrfCookie)) {
            throw new AppException(HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID", "刷新请求缺少有效的 CSRF 令牌");
        }
        return sessionResponse(authService.refresh(refreshToken, request.getHeader(HttpHeaders.USER_AGENT)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiEnvelope<Void>> logout(HttpServletRequest request, Authentication authentication) {
        authService.logout(cookie(request, REFRESH_COOKIE), CurrentUser.from(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearCookie(REFRESH_COOKIE, "/api/v1/auth").toString())
                .header(HttpHeaders.SET_COOKIE, clearCookie(CSRF_COOKIE, "/").toString())
                .body(ApiEnvelope.ok(null));
    }

    @GetMapping("/me")
    public ApiEnvelope<UserView> me(Authentication authentication) {
        return ApiEnvelope.ok(authService.me(CurrentUser.from(authentication)));
    }

    @PutMapping("/password")
    public ResponseEntity<ApiEnvelope<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        authService.changePassword(CurrentUser.from(authentication), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearCookie(REFRESH_COOKIE, "/api/v1/auth").toString())
                .header(HttpHeaders.SET_COOKIE, clearCookie(CSRF_COOKIE, "/").toString())
                .body(ApiEnvelope.ok(null));
    }

    private ResponseEntity<ApiEnvelope<SessionView>> sessionResponse(AuthService.IssuedSession issued) {
        Duration maxAge = Duration.ofDays(properties.refreshTokenDays());
        ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_COOKIE, issued.refreshToken())
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
        ResponseCookie csrfCookie = ResponseCookie.from(CSRF_COOKIE, issued.view().csrfToken())
                .httpOnly(false)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .header(HttpHeaders.SET_COOKIE, csrfCookie.toString())
                .body(ApiEnvelope.ok(issued.view()));
    }

    private ResponseCookie clearCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .httpOnly(REFRESH_COOKIE.equals(name))
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path(path)
                .maxAge(Duration.ZERO)
                .build();
    }

    private String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
