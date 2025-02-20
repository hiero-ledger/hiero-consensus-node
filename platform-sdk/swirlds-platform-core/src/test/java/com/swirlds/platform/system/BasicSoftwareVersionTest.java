// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Basic SoftwareVersion Tests")
class BasicSoftwareVersionTest {

    public final SoftwareVersion NO_VERSION = SoftwareVersion.NO_VERSION;
    public final SoftwareVersion VERSION_ONE = new BasicSoftwareVersion(1);
    public final SoftwareVersion VERSION_TWO = new BasicSoftwareVersion(2);

    @Test
    @DisplayName("Verify compareTo functionality")
    @SuppressWarnings("EqualsWithItself")
    void testCompareTo() {
        final int comparedToNoVersion = 1;
        final int comparedToSelf = 0;

        assertEquals(
                comparedToNoVersion,
                VERSION_ONE.compareTo(NO_VERSION),
                "Should always get back 1 when comparing to NO_VERSION.");
        assertEquals(
                comparedToNoVersion,
                VERSION_TWO.compareTo(NO_VERSION),
                "Should always get back 1 when comparing to NO_VERSION.");
        assertEquals(
                comparedToSelf, VERSION_ONE.compareTo(VERSION_ONE), "Should always get back 0 when comparing to self.");
        assertEquals(
                comparedToSelf, VERSION_TWO.compareTo(VERSION_TWO), "Should always get back 0 when comparing to self.");
        assertTrue(VERSION_ONE.compareTo(VERSION_TWO) < 0, "VERSION_ONE should be older than VERSION_TWO.");
        assertTrue(VERSION_TWO.compareTo(VERSION_ONE) > 0, "VERSION_TWO should be newer than VERSION_ONE.");
    }

    @Test
    @DisplayName("Verify the functionality of the PBJ record returned")
    void testPbjVersion() {
        assertNotNull(VERSION_ONE.getPbjSemanticVersion(), "PBJ record should not be null.");
        assertNotNull(VERSION_TWO.getPbjSemanticVersion(), "PBJ record should not be null.");
        assertEquals(
                VERSION_ONE.getPbjSemanticVersion(),
                VERSION_ONE.getPbjSemanticVersion(),
                "PBJ record should be the same.");
        assertNotEquals(
                VERSION_ONE.getPbjSemanticVersion(),
                VERSION_TWO.getPbjSemanticVersion(),
                "PBJ record should not be the same.");
    }

    @Test
    @DisplayName("Verify toString functionality")
    void testToString() {
        assertEquals("1", VERSION_ONE.toString(), "VERSION_ONE not reporting its version as 1.");
        assertEquals("2", VERSION_TWO.toString(), "VERSION_TWO not reporting its version as 2.");
    }
}
