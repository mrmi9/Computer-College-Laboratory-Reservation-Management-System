package com.college.labbooking.auth.application;

import com.college.labbooking.audit.AuditService;
import com.college.labbooking.auth.domain.RefreshSessionEntity;
import com.college.labbooking.auth.infrastructure.RefreshSessionRepository;
import com.college.labbooking.auth.web.SessionView;
import com.college.labbooking.auth.web.UserView;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.common.exception.LoginRejectedException;
import com.college.labbooking.common.exception.RefreshRejectedException;
import com.college.labbooking.identity.domain.PermissionEntity;
import com.college.labbooking.identity.domain.RoleEntity;
import com.college.labbooking.identity.domain.UserEntity;
import com.college.labbooking.identity.domain.UserStatus;
import com.college.labbooking.identity.infrastructure.UserRepository;
import com.college.labbooking.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final int LOCK_THRESHOLD = 5;
    private static final int LOCK_MINUTES = 15;

    private final UserRepository userRepository;
    private final RefreshSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenDigests tokenDigests;
    private final SecurityProperties properties;
    private final AuditService auditService;
    private final Clock clock;

    public AuthService(
            UserRepository userRepository,
            RefreshSessionRepository sessionRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            TokenDigests tokenDigests,
            SecurityProperties properties,
            AuditService auditService,
            Clock clock) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenDigests = tokenDigests;
        this.properties = properties;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = LoginRejectedException.class)
    public IssuedSession login(String username, String password, String userAgent) {
        Instant now = clock.instant();
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            auditService.record(null, username, "LOGIN_FAILED", "USER", null, false, Map.of("reason", "INVALID_CREDENTIALS"));
            throw invalidCredentials();
        }
        user.unlockIfElapsed(now);
        if (user.getStatus() == UserStatus.DISABLED) {
            auditService.record(user.getId(), username, "LOGIN_FAILED", "USER", user.getId().toString(), false, Map.of("reason", "ACCOUNT_DISABLED"));
            throw new LoginRejectedException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已被禁用");
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            auditService.record(user.getId(), username, "LOGIN_FAILED", "USER", user.getId().toString(), false, Map.of("reason", "ACCOUNT_LOCKED"));
            throw new LoginRejectedException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "账号因连续登录失败已临时锁定");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(now, LOCK_THRESHOLD, LOCK_MINUTES);
            userRepository.saveAndFlush(user);
            String reason = user.getStatus() == UserStatus.LOCKED ? "ACCOUNT_LOCKED" : "INVALID_CREDENTIALS";
            auditService.record(user.getId(), username, "LOGIN_FAILED", "USER", user.getId().toString(), false, Map.of("reason", reason));
            if (user.getStatus() == UserStatus.LOCKED) {
                throw new LoginRejectedException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "账号因连续登录失败已临时锁定");
            }
            throw invalidCredentials();
        }
        user.recordSuccessfulLogin();
        userRepository.save(user);
        IssuedSession issued = newSession(user, UUID.randomUUID(), now, userAgent);
        auditService.record(user.getId(), username, "LOGIN_SUCCESS", "USER", user.getId().toString(), true, Map.of());
        return issued;
    }

    @Transactional(noRollbackFor = RefreshRejectedException.class)
    public IssuedSession refresh(String refreshToken, String userAgent) {
        Instant now = clock.instant();
        RefreshSessionEntity previous = sessionRepository
                .findByTokenHashForUpdate(tokenDigests.sha256(refreshToken))
                .orElseThrow(() -> new RefreshRejectedException("REFRESH_TOKEN_INVALID", "刷新会话无效"));
        UserEntity user = previous.getUser();
        if (previous.getRevokedAt() != null || previous.getReplacedBy() != null) {
            sessionRepository.revokeFamily(previous.getSessionFamilyId(), "TOKEN_REUSE_DETECTED", now);
            auditService.record(user.getId(), user.getUsername(), "REFRESH_REUSE_DETECTED", "SESSION", previous.getId().toString(), false, Map.of());
            throw new RefreshRejectedException("REFRESH_TOKEN_REUSED", "检测到已轮换令牌被重复使用，相关会话已撤销");
        }
        if (!previous.getExpiresAt().isAfter(now)) {
            previous.revoke("EXPIRED", now);
            throw new RefreshRejectedException("REFRESH_TOKEN_EXPIRED", "刷新会话已过期");
        }
        if (user.getStatus() != UserStatus.ACTIVE || previous.getUserSessionVersion() != user.getSessionVersion()) {
            previous.revoke("USER_STATE_CHANGED", now);
            throw new RefreshRejectedException("SESSION_REVOKED", "用户状态或安全信息已变更，请重新登录");
        }
        UUID replacementId = UUID.randomUUID();
        previous.rotateTo(replacementId, now);
        IssuedSession issued = newSession(user, previous.getSessionFamilyId(), replacementId, now, userAgent);
        auditService.record(user.getId(), user.getUsername(), "SESSION_REFRESHED", "SESSION", replacementId.toString(), true, Map.of());
        return issued;
    }

    @Transactional
    public void logout(String refreshToken, CurrentUser currentUser) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessionRepository.findByTokenHashForUpdate(tokenDigests.sha256(refreshToken))
                    .ifPresent(session -> session.revoke("LOGOUT", clock.instant()));
        }
        auditService.record(currentUser.id(), currentUser.username(), "LOGOUT", "USER", Long.toString(currentUser.id()), true, Map.of());
    }

    @Transactional
    public void changePassword(CurrentUser currentUser, String currentPassword, String newPassword) {
        UserEntity user = userRepository
                .findDetailedById(currentUser.id())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PASSWORD_REUSE_NOT_ALLOWED", "新密码不能与当前密码相同");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        sessionRepository.revokeAllForUser(user.getId(), "PASSWORD_CHANGED", clock.instant());
        auditService.record(user.getId(), user.getUsername(), "PASSWORD_CHANGED", "USER", user.getId().toString(), true, Map.of());
    }

    @Transactional(readOnly = true)
    public UserView me(CurrentUser currentUser) {
        UserEntity user = userRepository
                .findDetailedById(currentUser.id())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        return toView(user);
    }

    private IssuedSession newSession(UserEntity user, UUID familyId, Instant now, String userAgent) {
        return newSession(user, familyId, UUID.randomUUID(), now, userAgent);
    }

    private IssuedSession newSession(UserEntity user, UUID familyId, UUID sessionId, Instant now, String userAgent) {
        String refreshToken = tokenDigests.newOpaqueToken(48);
        String csrfToken = tokenDigests.newOpaqueToken(32);
        RefreshSessionEntity session = new RefreshSessionEntity(
                sessionId,
                user,
                tokenDigests.sha256(refreshToken),
                familyId,
                user.getSessionVersion(),
                now,
                now.plus(properties.refreshTokenDays(), ChronoUnit.DAYS),
                limit(userAgent, 500));
        sessionRepository.save(session);
        JwtService.AccessToken accessToken = jwtService.issue(user, now);
        return new IssuedSession(
                refreshToken,
                new SessionView(accessToken.value(), accessToken.expiresAt(), csrfToken, toView(user)));
    }

    private UserView toView(UserEntity user) {
        Set<String> roles = user.getRoles().stream().map(RoleEntity::getCode).collect(Collectors.toUnmodifiableSet());
        Set<String> permissions = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(PermissionEntity::getCode)
                .collect(Collectors.toUnmodifiableSet());
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getUserType().name(),
                user.getDepartment(),
                user.getEmail(),
                user.getPhone(),
                roles,
                permissions,
                user.isMustChangePassword());
    }

    private LoginRejectedException invalidCredentials() {
        return new LoginRejectedException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record IssuedSession(String refreshToken, SessionView view) {}
}
