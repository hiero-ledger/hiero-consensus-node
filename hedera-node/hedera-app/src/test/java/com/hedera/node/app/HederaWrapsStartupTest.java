// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import com.hedera.cryptography.wraps.WRAPSLibraryBridge;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HederaWrapsStartupTest {
    @ParameterizedTest
    @CsvSource({"true, false", "true, true", "false, true"})
    void loadsNativeLibraryBeforeRequiredMaterial(final boolean historyEnabled, final boolean clprEnabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("tss.historyEnabled", historyEnabled)
                .withValue("clpr.enabled", clprEnabled)
                .getOrCreateConfig();
        final var nativeLoaded = new AtomicBoolean();
        try (final var wraps = mockStatic(WRAPSLibraryBridge.class)) {
            wraps.when(WRAPSLibraryBridge::getInstance).thenAnswer(_ -> {
                nativeLoaded.set(true);
                return null;
            });
            wraps.when(WRAPSLibraryBridge::isVerificationSupported).thenAnswer(_ -> {
                assertTrue(nativeLoaded.get(), "The native library must be loaded before its verifier key");
                return true;
            });
            wraps.when(WRAPSLibraryBridge::isProofSupported).thenAnswer(_ -> {
                assertTrue(nativeLoaded.get(), "The native library must be loaded before its proving parameters");
                return true;
            });

            Hedera.initializeWrapsLibraries(config);

            wraps.verify(WRAPSLibraryBridge::getInstance);
            wraps.verify(WRAPSLibraryBridge::isVerificationSupported);
            if (historyEnabled) {
                wraps.verify(WRAPSLibraryBridge::isProofSupported);
            } else {
                // A verification-only node must not incur public-parameter extraction and setup.
                wraps.verify(WRAPSLibraryBridge::isProofSupported, never());
            }
        }
    }

    @Test
    void doesNotLoadWrapsWhenHistoryAndClprAreDisabled() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("tss.historyEnabled", false)
                .withValue("clpr.enabled", false)
                .getOrCreateConfig();
        try (final var wraps = mockStatic(WRAPSLibraryBridge.class)) {
            Hedera.initializeWrapsLibraries(config);

            wraps.verifyNoInteractions();
        }
    }

    @Test
    void checksBothReadinessResultsWhenVerificationMaterialIsUnavailable() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("tss.historyEnabled", true)
                .withValue("clpr.enabled", false)
                .getOrCreateConfig();
        try (final var wraps = mockStatic(WRAPSLibraryBridge.class)) {
            wraps.when(WRAPSLibraryBridge::isVerificationSupported).thenReturn(false);
            wraps.when(WRAPSLibraryBridge::isProofSupported).thenReturn(true);

            Hedera.initializeWrapsLibraries(config);

            wraps.verify(WRAPSLibraryBridge::isVerificationSupported);
            wraps.verify(WRAPSLibraryBridge::isProofSupported);
        }
    }
}
