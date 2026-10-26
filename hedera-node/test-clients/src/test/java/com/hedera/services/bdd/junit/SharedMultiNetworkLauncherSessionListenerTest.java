// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.extensions.MultiNetworkGroupQueue;
import com.hedera.services.bdd.junit.extensions.NetworkGroup;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Tests for {@link SharedMultiNetworkLauncherSessionListener#validateOneGroupPerClass}.
 */
class SharedMultiNetworkLauncherSessionListenerTest {
    private final MultiNetworkGroupQueue queue = new MultiNetworkGroupQueue(4);

    @Test
    @DisplayName("A class locking the networks of two groups fails both groups; a one-group class is untouched")
    void classLockingTwoGroupsFailsBothGroups() throws Exception {
        final var plain = groupOf(CrossGroupLocks.class, "plain");
        final var manifest = groupOf(CrossGroupLocks.class, "manifest");
        final var mtls = groupOf(OneGroupLocks.class, "mtls");
        queue.register("plain", plain);
        queue.register("manifest", manifest);
        queue.register("mtls", mtls);

        SharedMultiNetworkLauncherSessionListener.validateOneGroupPerClass(
                List.of(CrossGroupLocks.class, OneGroupLocks.class), queue);

        for (final var testId : List.of("plain", "manifest")) {
            final var e = assertThrows(IllegalStateException.class, () -> queue.awaitTurn(testId));
            assertTrue(e.getMessage().contains(CrossGroupLocks.class.getName()), e.getMessage());
        }
        assertEquals(mtls, queue.awaitTurn("mtls"));
    }

    private static NetworkGroup groupOf(final Class<?> clazz, final String methodName) throws Exception {
        return NetworkGroup.of(clazz.getDeclaredMethod(methodName)
                .getAnnotation(MultiNetworkHapiTest.class)
                .value());
    }

    @Disabled("Fixture for validateOneGroupPerClass")
    @ResourceLock("ledgerA")
    @ResourceLock("ledgerA_manifest")
    static class CrossGroupLocks {
        @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
        Stream<DynamicTest> plain() {
            return Stream.empty();
        }

        @MultiNetworkHapiTest({@Network("ledgerA_manifest"), @Network("ledgerB_manifest")})
        Stream<DynamicTest> manifest() {
            return Stream.empty();
        }
    }

    @Disabled("Fixture for validateOneGroupPerClass")
    @ResourceLock("ledgerA_mtls")
    @ResourceLock("ledgerB_mtls")
    @ResourceLock("NETWORK")
    static class OneGroupLocks {
        @MultiNetworkHapiTest({@Network("ledgerA_mtls"), @Network("ledgerB_mtls")})
        Stream<DynamicTest> mtls() {
            return Stream.empty();
        }
    }
}
