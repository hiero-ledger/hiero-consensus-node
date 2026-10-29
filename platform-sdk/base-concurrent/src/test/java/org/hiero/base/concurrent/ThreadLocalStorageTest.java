// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import java.security.MessageDigest;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.internal.DefaultForkJoinWorkerThread;
import org.hiero.base.concurrent.internal.DefaultForkJoinWorkerThreadFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ThreadLocalStorage Tests")
public class ThreadLocalStorageTest {

    @Test
    void testGetMessageDigestAnd256() {
        final MessageDigest digest = ThreadLocalStorage.getMessageDigest("SHA-256");
        Assertions.assertNotNull(digest);
        final MessageDigest digest256 = ThreadLocalStorage.getMessageDigest256();
        Assertions.assertNotNull(digest256);
        Assertions.assertSame(digest, digest256);
    }

    @Test
    void testGetMessageDigestAnd384() {
        final MessageDigest digest = ThreadLocalStorage.getMessageDigest("SHA-384");
        Assertions.assertNotNull(digest);
        final MessageDigest digest384 = ThreadLocalStorage.getMessageDigest384();
        Assertions.assertNotNull(digest384);
        Assertions.assertSame(digest, digest384);
    }

    @Test
    void testGetMessageDigestDifferent() {
        final MessageDigest digest256 = ThreadLocalStorage.getMessageDigest256();
        final MessageDigest digest384 = ThreadLocalStorage.getMessageDigest384();
        Assertions.assertNotEquals(digest256, digest384);
    }

    @Test
    void testGetMessageDigest256Twice() {
        final MessageDigest digest256 = ThreadLocalStorage.getMessageDigest256();
        final MessageDigest digest256Again = ThreadLocalStorage.getMessageDigest256();
        Assertions.assertSame(digest256, digest256Again);
    }

    @Test
    void testGetMessageDigest384Twice() {
        final MessageDigest digest384 = ThreadLocalStorage.getMessageDigest384();
        final MessageDigest digest384Again = ThreadLocalStorage.getMessageDigest384();
        Assertions.assertSame(digest384, digest384Again);
    }

    @Test
    void testGetMessageDigestInDifferentThreads() throws InterruptedException {
        final AtomicReference<MessageDigest> digest256_1 = new AtomicReference<>();
        final AtomicReference<MessageDigest> digest256_2 = new AtomicReference<>();
        final AtomicReference<MessageDigest> digest384_1 = new AtomicReference<>();
        final AtomicReference<MessageDigest> digest384_2 = new AtomicReference<>();
        final Thread t1 = new Thread(() -> {
            digest256_1.set(ThreadLocalStorage.getMessageDigest("SHA-256"));
            digest384_1.set(ThreadLocalStorage.getMessageDigest("SHA-384"));
        });
        t1.start();
        final Thread t2 = new Thread(() -> {
            digest256_2.set(ThreadLocalStorage.getMessageDigest("SHA-256"));
            digest384_2.set(ThreadLocalStorage.getMessageDigest("SHA-384"));
        });
        t2.start();
        t1.join();
        t2.join();
        Assertions.assertNotNull(digest256_1.get());
        Assertions.assertNotNull(digest256_2.get());
        Assertions.assertNotNull(digest384_1.get());
        Assertions.assertNotNull(digest384_2.get());
        Assertions.assertNotEquals(digest256_1.get(), digest256_2.get());
        Assertions.assertNotEquals(digest384_1.get(), digest384_2.get());
    }

    @Test
    void testGetInDefaultForkJoinThread() throws ExecutionException, InterruptedException {
        final Thread currentThread = Thread.currentThread();
        final var threadFactory =
                new DefaultForkJoinWorkerThreadFactory(currentThread.getThreadGroup(), () -> "testThread", null);
        try (final ForkJoinPool pool = new ForkJoinPool(1, threadFactory, (_, _) -> {}, true)) {
            final ForkJoinTask<?> task = pool.submit(() -> {
                final MessageDigest digestFromTLS = ThreadLocalStorage.getMessageDigest256();
                final DefaultForkJoinWorkerThread thread = (DefaultForkJoinWorkerThread) Thread.currentThread();
                final MessageDigest digestFromThread = thread.getThreadLocalMessageDigest256();
                Assertions.assertSame(digestFromTLS, digestFromThread);
                Assertions.assertNotEquals(digestFromTLS, thread.getThreadLocalMessageDigest384());
                Assertions.assertNotEquals(ThreadLocalStorage.getMessageDigest384(), digestFromThread);
            });
            task.get();
        }
    }

    @Test
    void testMessageDigestCoupling() {
        final MessageDigest digest = ThreadLocalStorage.getMessageDigest256();
        final WritableMessageDigest digestWriter = ThreadLocalStorage.getWritableMessageDigest256();

        final byte[] bytes = new byte[] {1, 2, 4, 8, 16, 32, 64};

        digest.update(bytes);
        final byte[] h1 = digest.digest();
        digestWriter.writeBytes(bytes);
        final byte[] h2 = digestWriter.digest();
        Assertions.assertArrayEquals(h1, h2);

        digest.update(bytes);
        final byte[] h3 = digestWriter.digest();
        Assertions.assertArrayEquals(h1, h3);

        digestWriter.writeBytes(bytes);
        final byte[] h4 = digest.digest();
        Assertions.assertArrayEquals(h1, h4);
    }

    @Test
    void testCouplingInDefaultForkJoinThread() throws ExecutionException, InterruptedException {
        final byte[] bytes = new byte[] {4, 8, 16, 32, 64, 127};
        final Thread currentThread = Thread.currentThread();
        final var threadFactory =
                new DefaultForkJoinWorkerThreadFactory(currentThread.getThreadGroup(), () -> "testThread", null);
        try (final ForkJoinPool pool = new ForkJoinPool(1, threadFactory, (_, _) -> {}, true)) {
            final ForkJoinTask<?> task = pool.submit(() -> {
                final MessageDigest digest = ThreadLocalStorage.getMessageDigest256();
                final WritableMessageDigest digestWriter = ThreadLocalStorage.getWritableMessageDigest256();

                digest.update(bytes);
                final byte[] h1 = digest.digest();
                digestWriter.writeBytes(bytes);
                final byte[] h2 = digestWriter.digest();
                Assertions.assertArrayEquals(h1, h2);

                digest.update(bytes);
                final byte[] h3 = digestWriter.digest();
                Assertions.assertArrayEquals(h1, h3);

                digestWriter.writeBytes(bytes);
                final byte[] h4 = digest.digest();
                Assertions.assertArrayEquals(h1, h4);
            });
            task.get();
        }
    }

    @Test
    void testUnsupportedAlgorithm() {
        Assertions.assertThrows(
                UnsupportedOperationException.class, () -> ThreadLocalStorage.getMessageDigest("SHA-123"));
    }
}
