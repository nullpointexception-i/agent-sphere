package com.buukle.agent.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version normalization: tag-generated versions may carry a v prefix / pre-release suffix; missing segments count as 0. */
class VersionUtilsTest {

    @Test
    void equalVersionsAreAtLeast() {
        assertTrue(VersionUtils.isAtLeast("1.0.171", "1.0.171"));
        assertTrue(VersionUtils.isAtLeast("1.0.171.0", "1.0.171"));
        assertTrue(VersionUtils.isAtLeast("1.0", "1.0.0"));
    }

    @Test
    void newerVersionsAreAtLeast() {
        assertTrue(VersionUtils.isAtLeast("1.0.172", "1.0.171"));
        assertTrue(VersionUtils.isAtLeast("1.1.0", "1.0.171"));
        assertTrue(VersionUtils.isAtLeast("2.0", "1.9.9"));
    }

    @Test
    void olderVersionsAreRejected() {
        assertFalse(VersionUtils.isAtLeast("1.0.170", "1.0.171"));
        assertFalse(VersionUtils.isAtLeast("1.0.99", "1.0.100"));
        assertFalse(VersionUtils.isAtLeast("0.9.9", "1.0.0"));
    }

    @Test
    void prefixesAndSuffixesAreNormalized() {
        assertTrue(VersionUtils.isAtLeast("v1.0.171", "1.0.171"));
        assertTrue(VersionUtils.isAtLeast("1.0.171", "v1.0.171-alpha"));
        assertTrue(VersionUtils.isAtLeast("1.0.172-alpha", "1.0.171"));
        assertFalse(VersionUtils.isAtLeast("1.0.170-beta", "1.0.171"));
    }

    @Test
    void unknownCurrentVersionIsRejected() {
        assertFalse(VersionUtils.isAtLeast(null, "1.0.171"));
        assertFalse(VersionUtils.isAtLeast("", "1.0.171"));
        assertFalse(VersionUtils.isAtLeast("   ", "1.0.171"));
    }

    @Test
    void blankMinimumIsRejectedByUtilityCallerContract() {
        // Callers skip the gate when the minimum is blank; the utility itself must not claim compatibility.
        assertFalse(VersionUtils.isAtLeast("1.0.171", ""));
        assertFalse(VersionUtils.isAtLeast("1.0.171", null));
    }
}
