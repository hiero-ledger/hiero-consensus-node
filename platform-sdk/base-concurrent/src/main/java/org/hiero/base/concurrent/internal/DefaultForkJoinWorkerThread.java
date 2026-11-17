// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.internal;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

///
/// A default implementation of {@link ForkJoinWorkerThread}.
///
/// This thread implementation provides access to {@link MessageDigest} objects local to the
/// thread to avoid {@link ThreadLocal} lookups, which may be costly. More thread local objects
/// may be added in the future.
///
public class DefaultForkJoinWorkerThread extends ForkJoinWorkerThread {

    /**
     * The runnable to run on startup.
     */
    private final Runnable onStartup;

    ///
    /// A local field for SHA-256 message digest to avoid {@link ThreadLocal} lookups,
    /// which may be expensive sometimes. This field is coupled with {@link
    /// #localWritableMessageDigest256}, so all bytes written to one of them is visible in
    /// the other.
    ///
    private MessageDigest localMessageDigest256;
    ///
    /// A local field for SHA-256 writable message digest wrapped over the SHA-256
    /// message digest above.
    ///
    private WritableMessageDigest localWritableMessageDigest256;

    ///
    /// This field is similar to {@link #localMessageDigest256}, but for SHA-384.
    ///
    private MessageDigest localMessageDigest384;
    ///
    /// This field is similar to {@link #localWritableMessageDigest256}, but for SHA-384.
    ///
    private WritableMessageDigest localWritableMessageDigest384;

    /**
     * Create a new instance of {@link DefaultForkJoinWorkerThread}.
     *
     * @param name                 the name of the thread
     * @param group                the thread group
     * @param pool                 the fork join pool
     * @param preserveThreadLocals whether to preserve thread locals
     * @param onStartup            the runnable to run on startup
     */
    public DefaultForkJoinWorkerThread(
            @NonNull final String name,
            @NonNull final ThreadGroup group,
            @NonNull final ForkJoinPool pool,
            final boolean preserveThreadLocals,
            @Nullable final Runnable onStartup) {
        super(group, Objects.requireNonNull(pool, "pool must not be null"), preserveThreadLocals);
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(group, "group must not be null");
        setName(name);
        this.onStartup = onStartup;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (onStartup != null) {
            onStartup.run();
        }
    }

    ///
    /// Get a thread-local SHA-256 message digest. The caller is responsible for resetting the
    /// digest using {@link MessageDigest#digest()} or a similar method before this method is
    /// called again on the current thread.
    ///
    /// All bytes written to the returned message digest will be visible in the writable
    /// message digest obtained with {@link #getThreadLocalWritableMessageDigest256()} and
    /// vice versa.
    ///
    public final MessageDigest getThreadLocalMessageDigest256() {
        if (localMessageDigest256 == null) {
            initLocalMessageDigest256();
        }
        return localMessageDigest256;
    }

    ///
    /// This method is similar to {@link #getThreadLocalMessageDigest256()}, but for SHA-384.
    ///
    public final MessageDigest getThreadLocalMessageDigest384() {
        if (localMessageDigest384 == null) {
            initLocalMessageDigest384();
        }
        return localMessageDigest384;
    }

    ///
    /// Get a thread-local SHA-256 writable message digest wrapped over the message digest
    /// returned by {@link #getThreadLocalMessageDigest256()}. The caller is responsible for
    /// resetting the digest using {@link WritableMessageDigest#digest()} or a similar method
    /// before this method is called again on the current thread.
    ///
    /// All bytes written to the returned writable message digest will be visible in the
    /// message digest obtained with {@link #getThreadLocalMessageDigest256()} and vice versa.
    ///
    public final WritableMessageDigest getThreadLocalWritableMessageDigest256() {
        if (localWritableMessageDigest256 == null) {
            initLocalMessageDigest256();
        }
        return localWritableMessageDigest256;
    }

    ///
    /// This method is similar to {@link #getThreadLocalWritableMessageDigest256()}, but for SHA-384.
    ///
    public final WritableMessageDigest getThreadLocalWritableMessageDigest384() {
        if (localWritableMessageDigest384 == null) {
            initLocalMessageDigest384();
        }
        return localWritableMessageDigest384;
    }

    private void initLocalMessageDigest256() {
        assert localMessageDigest256 == null;
        assert localWritableMessageDigest256 == null;
        localMessageDigest256 = initLocalMessageDigest("SHA-256");
        localWritableMessageDigest256 = new WritableMessageDigest(localMessageDigest256);
    }

    private void initLocalMessageDigest384() {
        assert localMessageDigest384 == null;
        assert localWritableMessageDigest384 == null;
        localMessageDigest384 = initLocalMessageDigest("SHA-384");
        localWritableMessageDigest384 = new WritableMessageDigest(localMessageDigest384);
    }

    private MessageDigest initLocalMessageDigest(final String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (final NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialize " + algorithm + " digest", e);
        }
    }
}
