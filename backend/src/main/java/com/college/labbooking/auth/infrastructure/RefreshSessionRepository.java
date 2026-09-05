package com.college.labbooking.auth.infrastructure;

import com.college.labbooking.auth.domain.RefreshSessionEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshSessionRepository extends JpaRepository<RefreshSessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSessionEntity s where s.tokenHash = :tokenHash")
    Optional<RefreshSessionEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("update RefreshSessionEntity s set s.revokedAt = :now, s.revokeReason = :reason "
            + "where s.user.id = :userId and s.revokedAt is null")
    int revokeAllForUser(@Param("userId") long userId, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshSessionEntity s set s.revokedAt = :now, s.revokeReason = :reason "
            + "where s.sessionFamilyId = :familyId and s.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("reason") String reason, @Param("now") Instant now);
}
