// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.hints.AggregationAndVerificationKeys;
import com.hedera.cryptography.hints.HintsLibraryBridge;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * A preparation context with its own copy of the hinTS native library and its native caches.
 *
 * <p>The 3.18 bridge has no native context API. Loading both the bridge and native-support classes
 * with a separate loader gives native-support its own extraction cache, so it extracts and loads a
 * distinct native image. Loading just the bridge would instead reuse the original library path.
 * A bootstrap-only parent deliberately prevents sharing either library with the application,
 * including when a named module's classes would otherwise be delegated by the platform loader.
 * Only JDK types cross this boundary; the preprocessing result is copied into the application's
 * record type. Instances are retained for the node's lifetime, not created for each construction.
 *
 * <p>Each context still protects its own CRS cache. Key computation/validation and preprocessing
 * use separate instances so long preprocessing cannot block the key validations that round
 * handling may need. Neither instance shares native caches or a lock with active signing.
 * The pinned library's {@code jni_hints.rs} in v3.18.0 (commit
 * {@code 6fdf0faf5eff9ad54cc83e201641171e25d3e489}) caches the CRS in a native static for
 * all three preparation operations, so isolation must include the native image itself.
 */
class IsolatedHintsLibrary {
    private static final String NATIVE_SUPPORT_CLASS = "com.hedera.common.nativesupport.SingletonLoader";

    private final URLClassLoader loader;
    private final Object bridge;
    private final Method computeHints;
    private final Method validateHintsKey;
    private final Method preprocess;
    private final Method resetCache;
    private final Method verificationKey;
    private final Method aggregationKey;
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock(true);
    private byte[] cachedCrs;

    IsolatedHintsLibrary() {
        URLClassLoader initializedLoader = null;
        try {
            final var nativeSupport =
                    Class.forName(NATIVE_SUPPORT_CLASS, false, HintsLibraryBridge.class.getClassLoader());
            initializedLoader = new URLClassLoader(
                    new URL[] {locationOf(HintsLibraryBridge.class), locationOf(nativeSupport)}, null);
            loader = initializedLoader;
            final var bridgeType = Class.forName(HintsLibraryBridge.class.getName(), true, loader);
            requireIsolated(bridgeType);
            requireIsolated(Class.forName(NATIVE_SUPPORT_CLASS, false, loader));
            bridge = invoke(bridgeType.getMethod("getInstance"), null);
            computeHints = bridgeType.getMethod("computeHints", byte[].class, byte[].class, int.class, int.class);
            validateHintsKey =
                    bridgeType.getMethod("validateHintsKey", byte[].class, byte[].class, int.class, int.class);
            preprocess = bridgeType.getMethod(
                    "preprocess", byte[].class, int[].class, byte[][].class, long[].class, int.class);
            resetCache = bridgeType.getMethod("resetCache");
            final var resultType = preprocess.getReturnType();
            requireIsolated(resultType);
            verificationKey = resultType.getMethod("verificationKey");
            aggregationKey = resultType.getMethod("aggregationKey");
        } catch (ReflectiveOperationException | RuntimeException | Error e) {
            if (initializedLoader != null) {
                try {
                    initializedLoader.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cannot initialize an isolated hinTS preparation library", e);
        }
    }

    private void requireIsolated(final Class<?> type) {
        if (type.getClassLoader() != loader) {
            throw new IllegalStateException("hinTS preparation class is not isolated: " + type.getName());
        }
    }

    byte[] computeHints(final byte[] crs, final byte[] privateKey, final int partyId, final int n) {
        return withCrs(crs, () -> (byte[]) invoke(computeHints, bridge, crs, privateKey, partyId, n));
    }

    boolean validateHintsKey(final byte[] crs, final byte[] hintsKey, final int partyId, final int n) {
        return withCrs(crs, () -> (boolean) invoke(validateHintsKey, bridge, crs, hintsKey, partyId, n));
    }

    AggregationAndVerificationKeys preprocess(
            final byte[] crs, final int[] parties, final byte[][] hintsKeys, final long[] weights, final int n) {
        return withCrs(crs, () -> {
            final var result = invoke(preprocess, bridge, crs, parties, hintsKeys, weights, n);
            return result == null
                    ? null
                    : new AggregationAndVerificationKeys(
                            (byte[]) invoke(verificationKey, result), (byte[]) invoke(aggregationKey, result));
        });
    }

    private <T> T withCrs(final byte[] crs, final Supplier<T> operation) {
        requireNonNull(crs);
        final var readLock = cacheLock.readLock();
        readLock.lock();
        try {
            if (Arrays.equals(cachedCrs, crs)) {
                return operation.get();
            }
        } finally {
            readLock.unlock();
        }
        final var writeLock = cacheLock.writeLock();
        writeLock.lock();
        try {
            if (!Arrays.equals(cachedCrs, crs)) {
                invoke(resetCache, bridge);
                cachedCrs = crs.clone();
            }
            return operation.get();
        } finally {
            writeLock.unlock();
        }
    }

    private static URL locationOf(final Class<?> type) {
        final var source = requireNonNull(type.getProtectionDomain().getCodeSource(), "Missing hinTS library location");
        return source.getLocation();
    }

    private static Object invoke(final Method method, final Object target, final Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            final var cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Isolated hinTS native operation failed", cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke isolated hinTS native operation", e);
        }
    }
}
