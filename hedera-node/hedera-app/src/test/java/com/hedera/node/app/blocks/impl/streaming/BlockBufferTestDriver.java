// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only helper that drives a {@link BlockBufferService}'s internals deterministically from other packages: it marks
 * the service started (without launching the async pruning worker) and runs a single synchronous pruning pass. This is
 * the same reflection approach {@code BlockBufferServiceTest} uses internally, exposed so cross-package tests (e.g. the
 * ISS-block uploader tests) can exercise the real buffer against the real reader/coordinator.
 */
public final class BlockBufferTestDriver {
    private static final VarHandle IS_STARTED;
    private static final MethodHandle CHECK_BUFFER;

    static {
        try {
            final Lookup lookup = MethodHandles.privateLookupIn(BlockBufferService.class, MethodHandles.lookup());
            IS_STARTED = lookup.findVarHandle(BlockBufferService.class, "isStarted", AtomicBoolean.class);
            final Method checkBuffer = BlockBufferService.class.getDeclaredMethod("checkBuffer");
            checkBuffer.setAccessible(true);
            CHECK_BUFFER = lookup.unreflect(checkBuffer);
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private BlockBufferTestDriver() {}

    /** Marks the service started without launching its async pruning worker (so pruning stays deterministic). */
    public static void markStarted(final BlockBufferService service) {
        ((AtomicBoolean) IS_STARTED.get(service)).set(true);
    }

    /** Runs one synchronous pruning pass (the body of the periodic worker). */
    public static void checkBuffer(final BlockBufferService service) {
        try {
            CHECK_BUFFER.invoke(service);
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
