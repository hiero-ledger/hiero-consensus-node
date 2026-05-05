// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HederaGossipAvailabilityTest {
    @ParameterizedTest
    @EnumSource(value = PlatformStatus.class, names = {"ACTIVE", "CHECKING", "FREEZING"})
    void gossipAvailableForStatusesThatCanCreatePriorityEvents(final PlatformStatus status) {
        assertTrue(Hedera.isGossipAvailableForNodeTransactions(status));
    }

    @ParameterizedTest
    @EnumSource(
            value = PlatformStatus.class,
            names = {"ACTIVE", "CHECKING", "FREEZING"},
            mode = EnumSource.Mode.EXCLUDE)
    void gossipUnavailableForStatusesThatCannotCreatePriorityEvents(final PlatformStatus status) {
        assertFalse(Hedera.isGossipAvailableForNodeTransactions(status));
    }
}
