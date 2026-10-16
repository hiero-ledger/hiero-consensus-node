// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.impl.HintsControllerImpl.decodeCrsUpdate;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.cryptography.hints.HintsLibraryBridge;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IsolatedHintsLibraryTest {
    private static final HintsLibraryImpl SIGNING = new HintsLibraryImpl();
    private static final IsolatedHintsLibrary KEYS = new IsolatedHintsLibrary();
    private static final IsolatedHintsLibrary PREPROCESSING = new IsolatedHintsLibrary();
    private static final Bytes MESSAGE = Bytes.wrap("active signing survives preparation cache changes");

    @Test
    void bridgeAndNativeLoaderBelongToTheIsolatedLoaderEvenOnTheModulePath() throws Exception {
        final var bridgeField = IsolatedHintsLibrary.class.getDeclaredField("bridge");
        bridgeField.setAccessible(true);
        final var bridge = bridgeField.get(KEYS);
        final var loaderField = IsolatedHintsLibrary.class.getDeclaredField("loader");
        loaderField.setAccessible(true);
        final var loader = (ClassLoader) loaderField.get(KEYS);

        assertNotSame(HintsLibraryBridge.class.getClassLoader(), loader);
        assertSame(loader, bridge.getClass().getClassLoader());
        assertSame(
                loader,
                Class.forName("com.hedera.common.nativesupport.SingletonLoader", false, loader)
                        .getClassLoader());
        assertSame(
                loader,
                Class.forName("com.hedera.common.nativesupport.NativeBinary", false, loader)
                        .getClassLoader());
    }

    @Test
    void separatePreparationCachesCanSwitchCapacityWithoutChangingTheActiveSigningCache() {
        final var activeCrs = contributedCrs(8, 1);
        final var privateKey = SIGNING.newBlsPrivateKey();
        final var activeHint = KEYS.computeHints(activeCrs, privateKey.toByteArray(), 0, 8);
        assertNotNull(activeHint);
        final var activeKeys =
                PREPROCESSING.preprocess(activeCrs, new int[] {0}, new byte[][] {activeHint}, new long[] {1L}, 8);
        assertNotNull(activeKeys);
        final var partialSignature = SIGNING.signBls(MESSAGE, privateKey);
        assertSigns(activeCrs, activeKeys.aggregationKey(), activeKeys.verificationKey(), partialSignature);

        for (final int capacity : new int[] {16, 8}) {
            final var nextCrs = contributedCrs(capacity, capacity);
            final var nextKey = SIGNING.newBlsPrivateKey();
            final var nextHint = KEYS.computeHints(nextCrs, nextKey.toByteArray(), 0, capacity);
            assertNotNull(nextHint);
            assertTrue(KEYS.validateHintsKey(nextCrs, nextHint, 0, capacity));
            assertSigns(activeCrs, activeKeys.aggregationKey(), activeKeys.verificationKey(), partialSignature);

            final var nextKeys = PREPROCESSING.preprocess(
                    nextCrs, new int[] {0}, new byte[][] {nextHint}, new long[] {1L}, capacity);
            assertNotNull(nextKeys);
            assertSigns(activeCrs, activeKeys.aggregationKey(), activeKeys.verificationKey(), partialSignature);

            // Switching the key context back must not reset the preprocessing context or the parent image.
            assertTrue(KEYS.validateHintsKey(activeCrs, activeHint, 0, 8));
            final var repeatedKeys = PREPROCESSING.preprocess(
                    nextCrs, new int[] {0}, new byte[][] {nextHint}, new long[] {1L}, capacity);
            assertNotNull(repeatedKeys);
            assertArrayEquals(nextKeys.verificationKey(), repeatedKeys.verificationKey());
            assertArrayEquals(nextKeys.aggregationKey(), repeatedKeys.aggregationKey());
            assertSigns(activeCrs, activeKeys.aggregationKey(), activeKeys.verificationKey(), partialSignature);
        }
    }

    @Test
    void cachesAnOwnedCrsIdentityWhenTheCallerReusesItsInputBuffer() {
        final var originalCrs = contributedCrs(8, 31);
        final var replacementCrs = contributedCrs(8, 32);
        final var inputBuffer = originalCrs.clone();
        final var privateKey = SIGNING.newBlsPrivateKey().toByteArray();
        final var originalHint = KEYS.computeHints(inputBuffer, privateKey, 0, 8);
        assertNotNull(originalHint);

        System.arraycopy(replacementCrs, 0, inputBuffer, 0, inputBuffer.length);
        final var replacementHint = KEYS.computeHints(inputBuffer, privateKey, 0, 8);
        assertNotNull(replacementHint);
        assertNotEquals(Bytes.wrap(originalHint), Bytes.wrap(replacementHint));
        assertTrue(KEYS.validateHintsKey(replacementCrs, replacementHint, 0, 8));
        assertFalse(KEYS.validateHintsKey(replacementCrs, originalHint, 0, 8));
        assertTrue(KEYS.validateHintsKey(originalCrs, originalHint, 0, 8));
    }

    @Test
    void preservesNativeInvalidInputResultsAndRecoversAfterRejectedInput() {
        final var crs = contributedCrs(8, 63);
        final var privateKey = SIGNING.newBlsPrivateKey().toByteArray();
        final var hint = KEYS.computeHints(crs, privateKey, 0, 8);
        assertNotNull(hint);

        assertNull(KEYS.computeHints(crs, privateKey, -1, 8));
        assertFalse(KEYS.validateHintsKey(new byte[0], hint, 0, 8));
        assertNull(PREPROCESSING.preprocess(crs, new int[] {0}, new byte[0][], new long[] {1L}, 8));

        assertTrue(KEYS.validateHintsKey(crs, hint, 0, 8));
        assertNotNull(PREPROCESSING.preprocess(crs, new int[] {0}, new byte[][] {hint}, new long[] {1L}, 8));
    }

    private static byte[] contributedCrs(final int capacity, final int contribution) {
        final var initial = SIGNING.newCrs((short) capacity);
        final var entropy = new byte[32];
        entropy[0] = (byte) contribution;
        return decodeCrsUpdate(initial.length(), SIGNING.updateCrs(initial, Bytes.wrap(entropy)))
                .crs()
                .toByteArray();
    }

    private static void assertSigns(
            final byte[] crs, final byte[] aggregationKey, final byte[] verificationKey, final Bytes partialSignature) {
        final var wrappedCrs = Bytes.wrap(crs);
        final var wrappedAggregationKey = Bytes.wrap(aggregationKey);
        final var wrappedVerificationKey = Bytes.wrap(verificationKey);
        assertTrue(SIGNING.verifyBls(wrappedCrs, partialSignature, MESSAGE, wrappedAggregationKey, 0));
        final var aggregate = SIGNING.aggregateSignatures(
                wrappedCrs, wrappedAggregationKey, wrappedVerificationKey, Map.of(0, partialSignature));
        assertNotNull(aggregate);
        assertTrue(SIGNING.verifyAggregate(aggregate, MESSAGE, wrappedVerificationKey, 1L, 3L));
    }
}
