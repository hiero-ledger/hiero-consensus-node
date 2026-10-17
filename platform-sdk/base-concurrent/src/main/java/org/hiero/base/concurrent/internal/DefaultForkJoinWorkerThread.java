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

/**
 * A default implementation of {@link ForkJoinWorkerThread}.
 */
public class DefaultForkJoinWorkerThread extends ForkJoinWorkerThread {

    /**
     * The runnable to run on startup.
     */
    private final Runnable onStartup;

    private MessageDigest localMessageDigest256;
    private WritableMessageDigest localWritableMessageDigest256;

    private MessageDigest localMessageDigest384;
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

    public final MessageDigest getThreadLocalMessageDigest256() {
        if (localMessageDigest256 == null) {
            initLocalMessageDigest256();
        }
        return localMessageDigest256;
    }

    public final MessageDigest getThreadLocalMessageDigest384() {
        if (localMessageDigest384 == null) {
            initLocalMessageDigest384();
        }
        return localMessageDigest384;
    }

    public final WritableMessageDigest getThreadLocalWritableMessageDigest256() {
        if (localWritableMessageDigest256 == null) {
            initLocalMessageDigest256();
        }
        return localWritableMessageDigest256;
    }

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
        assert localMessageDigest256 == null;
        assert localWritableMessageDigest256 == null;
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
