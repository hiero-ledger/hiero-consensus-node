// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ;
import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.MultiNetworkLeakyHapiTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Tests for {@link ClprNetworkLocksProvider}.
 */
class ClprNetworkLocksProviderTest {
    private final ClprNetworkLocksProvider provider = new ClprNetworkLocksProvider();

    @Test
    @DisplayName("A test locks each of its networks, by resolved name, as a reader")
    void readerLocksEachNetwork() throws Exception {
        assertEquals(Map.of("ledgerA", READ, "ledgerB", READ), locksOf(ReaderTests.class, "byValue"));
        assertEquals(Map.of("ledgerA_mtls", READ, "ledgerB_mtls", READ), locksOf(ReaderTests.class, "byName"));
    }

    @Test
    @DisplayName("A leaky method locks its networks for writing; its non-leaky siblings stay readers")
    void leakyMethodLocksForWriting() throws Exception {
        assertEquals(Map.of("ledgerA", READ_WRITE, "ledgerB", READ_WRITE), locksOf(ReaderTests.class, "leaky"));
        assertEquals(Map.of("ledgerA", READ, "ledgerB", READ), locksOf(ReaderTests.class, "byValue"));
    }

    @Test
    @DisplayName("Every test of a leaky class locks its networks for writing")
    void leakyClassLocksForWriting() throws Exception {
        assertEquals(
                Map.of("ledgerA_restart", READ_WRITE, "ledgerB_restart", READ_WRITE),
                locksOf(LeakyClassTests.class, "restart"));
    }

    @Test
    @DisplayName("A method that is not a multi-network test gets no locks")
    void nonMultiNetworkMethodGetsNoLocks() throws Exception {
        assertTrue(provider.provideForMethod(
                        List.of(), ReaderTests.class, ReaderTests.class.getDeclaredMethod("notMultiNetwork"))
                .isEmpty());
    }

    @Test
    @DisplayName("@MultiNetworkHapiTest is wired to this provider")
    void multiNetworkHapiTestUsesProvider() {
        final var lock = MultiNetworkHapiTest.class.getAnnotation(ResourceLock.class);
        assertArrayEquals(new Class<?>[] {ClprNetworkLocksProvider.class}, lock.providers());
    }

    private Map<String, ResourceAccessMode> locksOf(final Class<?> testClass, final String methodName)
            throws Exception {
        return provider.provideForMethod(List.of(), testClass, testClass.getDeclaredMethod(methodName)).stream()
                .collect(Collectors.toMap(
                        ClprNetworkLocksProvider.Lock::getKey, ClprNetworkLocksProvider.Lock::getAccessMode));
    }

    @Disabled("Fixture for ClprNetworkLocksProviderTest")
    static class ReaderTests {
        @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
        Stream<DynamicTest> byValue() {
            return Stream.empty();
        }

        @MultiNetworkHapiTest({@Network(name = "ledgerA_mtls"), @Network(name = "ledgerB_mtls")})
        Stream<DynamicTest> byName() {
            return Stream.empty();
        }

        @MultiNetworkLeakyHapiTest
        @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
        Stream<DynamicTest> leaky() {
            return Stream.empty();
        }

        void notMultiNetwork() {}
    }

    @Disabled("Fixture for ClprNetworkLocksProviderTest")
    @MultiNetworkLeakyHapiTest
    static class LeakyClassTests {
        @MultiNetworkHapiTest({@Network("ledgerA_restart"), @Network("ledgerB_restart")})
        Stream<DynamicTest> restart() {
            return Stream.empty();
        }
    }
}
