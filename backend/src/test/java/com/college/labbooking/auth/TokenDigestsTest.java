package com.college.labbooking.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.college.labbooking.auth.application.TokenDigests;
import org.junit.jupiter.api.Test;

class TokenDigestsTest {
    private final TokenDigests tokenDigests = new TokenDigests();

    @Test
    void opaqueTokensAreRandomUrlSafeAndOnlyTheirDigestNeedsPersistence() {
        String first = tokenDigests.newOpaqueToken(32);
        String second = tokenDigests.newOpaqueToken(32);

        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+").isNotEqualTo(second);
        assertThat(tokenDigests.sha256(first)).hasSize(64).doesNotContain(first);
    }

    @Test
    void digestComparisonRejectsNullAndDifferentValues() {
        assertThat(tokenDigests.constantTimeEquals("same", "same")).isTrue();
        assertThat(tokenDigests.constantTimeEquals("left", "right")).isFalse();
        assertThat(tokenDigests.constantTimeEquals(null, "right")).isFalse();
    }
}
