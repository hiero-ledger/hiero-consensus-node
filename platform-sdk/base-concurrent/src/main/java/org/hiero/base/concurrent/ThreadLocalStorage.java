// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.hiero.base.concurrent.internal.DefaultForkJoinWorkerThread;

public final class ThreadLocalStorage {

    private static final ThreadLocal<MessageDigest> TLS_MESSAGE_DIGEST_SHA_256 = new ThreadLocal<>();
    private static final ThreadLocal<WritableMessageDigest> TLS_WRITABLE_MESSAGE_DIGEST_SHA_256 = new ThreadLocal<>();

    private static final ThreadLocal<MessageDigest> TLS_MESSAGE_DIGEST_SHA_384 = new ThreadLocal<>();
    private static final ThreadLocal<WritableMessageDigest> TLS_WRITABLE_MESSAGE_DIGEST_SHA_384 = new ThreadLocal<>();

    private ThreadLocalStorage() {}

    public static MessageDigest getMessageDigest256() {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalMessageDigest256();
        } else {
            return getMessageDigestFromTLS("SHA-256", TLS_MESSAGE_DIGEST_SHA_256, TLS_WRITABLE_MESSAGE_DIGEST_SHA_256);
        }
    }

    public static MessageDigest getMessageDigest384() {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalMessageDigest384();
        } else {
            return getMessageDigestFromTLS("SHA-384", TLS_MESSAGE_DIGEST_SHA_384, TLS_WRITABLE_MESSAGE_DIGEST_SHA_384);
        }
    }

    private static MessageDigest getMessageDigestFromTLS(
            final String algorithm,
            final ThreadLocal<MessageDigest> tlsMD,
            final ThreadLocal<WritableMessageDigest> tlsWMD) {
        MessageDigest md = tlsMD.get();
        if (md == null) {
            initLocalMessageDigest(algorithm, tlsMD, tlsWMD);
            md = tlsMD.get();
        }
        return md;
    }

    public static WritableMessageDigest getWritableMessageDigest256() {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalWritableMessageDigest256();
        } else {
            return getWritableMessageDigestFromTLS(
                    "SHA-256", TLS_MESSAGE_DIGEST_SHA_256, TLS_WRITABLE_MESSAGE_DIGEST_SHA_256);
        }
    }

    public static WritableMessageDigest getWritableMessageDigest384() {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalWritableMessageDigest384();
        } else {
            return getWritableMessageDigestFromTLS(
                    "SHA-384", TLS_MESSAGE_DIGEST_SHA_384, TLS_WRITABLE_MESSAGE_DIGEST_SHA_384);
        }
    }

    private static WritableMessageDigest getWritableMessageDigestFromTLS(
            final String algorithm,
            final ThreadLocal<MessageDigest> tlsMD,
            final ThreadLocal<WritableMessageDigest> tlsWMD) {
        WritableMessageDigest wmd = tlsWMD.get();
        if (wmd == null) {
            initLocalMessageDigest(algorithm, tlsMD, tlsWMD);
            wmd = tlsWMD.get();
        }
        return wmd;
    }

    private static void initLocalMessageDigest(
            final String algorithm,
            final ThreadLocal<MessageDigest> tlsMD,
            final ThreadLocal<WritableMessageDigest> tlsWMD) {
        assert tlsMD.get() == null;
        assert tlsWMD.get() == null;
        try {
            final MessageDigest md = MessageDigest.getInstance(algorithm);
            tlsMD.set(md);
            final WritableMessageDigest wmd = new WritableMessageDigest(md);
            tlsWMD.set(wmd);
        } catch (final NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialize " + algorithm + " digest", e);
        }
    }
}
