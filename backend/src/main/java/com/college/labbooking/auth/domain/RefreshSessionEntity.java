package com.college.labbooking.auth.domain;

import com.college.labbooking.identity.domain.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_session")
public class RefreshSessionEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "session_family_id", nullable = false)
    private UUID sessionFamilyId;

    @Column(name = "user_session_version", nullable = false)
    private int userSessionVersion;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent")
    private String userAgent;

    protected RefreshSessionEntity() {}

    public RefreshSessionEntity(
            UUID id,
            UserEntity user,
            String tokenHash,
            UUID sessionFamilyId,
            int userSessionVersion,
            Instant issuedAt,
            Instant expiresAt,
            String userAgent) {
        this.id = id;
        this.user = user;
        this.tokenHash = tokenHash;
        this.sessionFamilyId = sessionFamilyId;
        this.userSessionVersion = userSessionVersion;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
    }

    public UUID getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public UUID getSessionFamilyId() {
        return sessionFamilyId;
    }

    public int getUserSessionVersion() {
        return userSessionVersion;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    public void rotateTo(UUID replacementId, Instant now) {
        lastUsedAt = now;
        revokedAt = now;
        revokeReason = "ROTATED";
        replacedBy = replacementId;
    }

    public void revoke(String reason, Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
            revokeReason = reason;
        }
    }
}
