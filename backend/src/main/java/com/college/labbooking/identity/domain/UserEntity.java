package com.college.labbooking.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "sys_user")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "real_name", nullable = false)
    private String realName;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false)
    private UserType userType;

    private String department;
    private String email;
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "booking_frozen_until")
    private Instant bookingFrozenUntil;

    @Column(name = "no_show_count", nullable = false)
    private int noShowCount;

    @Column(name = "session_version", nullable = false)
    private int sessionVersion;

    @Version
    private long version;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "sys_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<RoleEntity> roles = new LinkedHashSet<>();

    protected UserEntity() {}

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRealName() {
        return realName;
    }

    public UserType getUserType() {
        return userType;
    }

    public String getDepartment() {
        return department;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getBookingFrozenUntil() {
        return bookingFrozenUntil;
    }

    public int getSessionVersion() {
        return sessionVersion;
    }

    public Set<RoleEntity> getRoles() {
        return Set.copyOf(roles);
    }

    public void recordFailedLogin(Instant now, int threshold, long lockMinutes) {
        failedLoginCount++;
        if (failedLoginCount >= threshold) {
            status = UserStatus.LOCKED;
            lockedUntil = now.plusSeconds(lockMinutes * 60);
        }
    }

    public void recordSuccessfulLogin() {
        failedLoginCount = 0;
        lockedUntil = null;
        if (status == UserStatus.LOCKED) {
            status = UserStatus.ACTIVE;
        }
    }

    public void unlockIfElapsed(Instant now) {
        if (status == UserStatus.LOCKED && lockedUntil != null && !lockedUntil.isAfter(now)) {
            recordSuccessfulLogin();
        }
    }

    public void changePassword(String encodedPassword) {
        passwordHash = encodedPassword;
        mustChangePassword = false;
        sessionVersion++;
    }
}
