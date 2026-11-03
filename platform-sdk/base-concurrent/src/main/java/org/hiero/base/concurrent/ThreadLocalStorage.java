// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.hiero.base.concurrent.internal.DefaultForkJoinWorkerThread;

///
/// A set of utility methods to work with thread-local objects.
///
/// If the current thread is an instance of {@link DefaultForkJoinWorkerThread}, the requested
/// objects are obtained directly from the threads, where they are stored as object fields. This
/// is faster than {@link ThreadLocal} lookups, which are used, when the current thread is of
/// a different type.
///
/// All methods in this class are thread safe, they return objects specific to the calling
/// thread. Same method called in different threads will return different objects, similar to
/// how {@link ThreadLocal} works.
///
public final class ThreadLocalStorage {

    private static final ThreadLocal<MessageDigest> TLS_MESSAGE_DIGEST_SHA_256 = new ThreadLocal<>();
    private static final ThreadLocal<WritableMessageDigest> TLS_WRITABLE_MESSAGE_DIGEST_SHA_256 = new ThreadLocal<>();

    private static final ThreadLocal<MessageDigest> TLS_MESSAGE_DIGEST_SHA_384 = new ThreadLocal<>();
    private static final ThreadLocal<WritableMessageDigest> TLS_WRITABLE_MESSAGE_DIGEST_SHA_384 = new ThreadLocal<>();

    private ThreadLocalStorage() {}

    ///
    /// Get a thread local message digest for a given algorithm name. Currently, only
    /// SHA-256 and SHA-384 are supported, support for more algorithms may be added.
    ///
    /// The message digest returned by this method is coupled with the writable message
    /// digest returned by {@link #getWritableMessageDigest(String)} on the same thread.
    /// All bytes written to one of them are visible in the other.
    ///
    /// @param algorithm message digest algorithm name, SHA-256 or SHA-384
    /// @return message digest that is local to the current thread
    ///
    public static MessageDigest getMessageDigest(final String algorithm) {
        return switch (algorithm) {
            case "SHA-256" -> getMessageDigest256();
            case "SHA-384" -> getMessageDigest384();
            default -> throw new UnsupportedOperationException("Unsupported digest type: " + algorithm);
        };
    }

    ///
    /// Get a SHA-256 {@link MessageDigest}. This object shares bytes with a writable SHA-256
    /// message digest returned by {@link #getWritableMessageDigest256()}.
    ///
    /// The caller is responsible for resetting the digest using {@link MessageDigest#digest()}
    /// or a similar method before this method is called again on the current thread.
    ///
    public static MessageDigest getMessageDigest256() {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalMessageDigest256();
        } else {
            return getMessageDigestFromTL("SHA-256", TLS_MESSAGE_DIGEST_SHA_256, TLS_WRITABLE_MESSAGE_DIGEST_SHA_256);
        }
    }

    ///
    /// This method is similar to {@link #getMessageDigest256()}, but for SHA-384.
    ///
    public static MessageDigest getMessageDigest384() {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalMessageDigest384();
        } else {
            return getMessageDigestFromTL("SHA-384", TLS_MESSAGE_DIGEST_SHA_384, TLS_WRITABLE_MESSAGE_DIGEST_SHA_384);
        }
    }

    ///
    /// Get a message digest from the provided {@link ThreadLocal}. If the message digest
    /// is null, it gets initialized first. The corresponding coupled writable message digest
    /// is initialized, too.
    ///
    private static MessageDigest getMessageDigestFromTL(
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

    ///
    /// Get a thread local writable message digest for a given algorithm name. Currently,
    /// only SHA-256 and SHA-384 are supported, support for more algorithms may be added.
    ///
    /// The writable message digest returned by this method is coupled with the writable
    /// message digest returned by {@link #getMessageDigest(String)} on the same thread.
    /// All bytes written to one of them are visible in the other.
    ///
    /// @param algorithm message digest algorithm name, SHA-256 or SHA-384
    /// @return writable message digest that is local to the current thread
    ///
    public static WritableMessageDigest getWritableMessageDigest(final String algorithm) {
        return switch (algorithm) {
            case "SHA-256" -> getWritableMessageDigest256();
            case "SHA-384" -> getWritableMessageDigest384();
            default -> throw new UnsupportedOperationException("Unsupported digest type: " + algorithm);
        };
    }

    ///
    /// Get a writable SHA-256 {@link WritableMessageDigest}. This object shares bytes with a
    /// SHA-256 message digest returned by {@link #getMessageDigest256()}.
    ///
    /// The caller is responsible for resetting the digest using {@link WritableMessageDigest#digest()}
    /// or a similar method before this method is called again on the current thread.
    ///
    public static WritableMessageDigest getWritableMessageDigest256() {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalWritableMessageDigest256();
        } else {
            return getWritableMessageDigestFromTL(
                    "SHA-256", TLS_MESSAGE_DIGEST_SHA_256, TLS_WRITABLE_MESSAGE_DIGEST_SHA_256);
        }
    }

    ///
    /// This method is similar to {@link #getWritableMessageDigest256()}, but for SHA-384.
    ///
    public static WritableMessageDigest getWritableMessageDigest384() {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalWritableMessageDigest384();
        } else {
            return getWritableMessageDigestFromTL(
                    "SHA-384", TLS_MESSAGE_DIGEST_SHA_384, TLS_WRITABLE_MESSAGE_DIGEST_SHA_384);
        }
    }

    ///
    /// Get a writable message digest from thread local storage, either the current thread
    /// or a {@link ThreadLocal}. If the message digest is null, it gets initialized first.
    ///
    private static WritableMessageDigest getWritableMessageDigestFromTL(
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
