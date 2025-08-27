// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hiero.base.crypto.TransactionSignature;

/**
 * A {@link Future} that waits on a {@link List} of {@link SignatureVerification}s to complete signature checks, and
 * yields a single {@link SignatureVerification} representing the result of all those verifications.
 *
 * <p>For example, if an instance is created with a {@link KeyList} based {@link Key}, then when {@link #get()} or
 * {@link #get(long, TimeUnit)} are called, it will return a {@link SignatureVerification} where the
 * {@link SignatureVerification#key()} is that key, and {@link SignatureVerification#passed()} is true if,
 * and only if, every {@link SignatureVerification} in the List has also passed.
 *
 * <p>If an instance is created with a {@link ThresholdKey} based {@link Key}, then when {@link #get()} or
 * {@link #get(long, TimeUnit)} are called, it will return a {@link SignatureVerification} where the
 * {@link SignatureVerification#key()} is that key, and {@link SignatureVerification#passed()} is true if,
 * and only if, it did not encounter {@link #numCanFail} failing verifications in the list.
 */
final class CompoundSignatureVerificationFuture implements SignatureVerificationFuture {
    /**
     * The Key we verified. This will *never* be null, because we would not have attempted signature verification
     * without having a key. If an EVM address was used, we would have already extracted the key, so it can be
     * provided here.
     */
    private final Key key;
    /** Optional: used only with some ECDSA_SECP256K1 keys, evm alias associated with the key. */
    private final Bytes evmAlias;
    /**
     * The list of {@link Future<TransactionSignature>}s. This list cannot be empty or contain null.
     */
    private final List<Future<SignatureVerification>> futures;
    /**
     * Whether *this* future has been canceled. Used for properly implementing {@link Future} semantics.
     */
    private boolean canceled = false;
    /**
     * The number of futures with verifications that can fail (passed == false) before the {@link SignatureVerification}
     * created by this instance will have failed.
     */
    private final int numCanFail;

    /**
     * Create a new instance.
     *
     * @param key The key associated with this sig check. Cannot be null.
     * @param evmAlias The evm alias, if any (only if the key is an ECDSA_SECP256K1 key)
     * @param futures The {@link TransactionSignature}s, from which the pass/fail status of the
     * {@link SignatureVerification} is derived. This list must contain at least one element.
     */
    CompoundSignatureVerificationFuture(
            @NonNull final Key key,
            @Nullable final Bytes evmAlias,
            @NonNull final List<Future<SignatureVerification>> futures,
            final int numCanFail) {
        this.key = requireNonNull(key);
        this.evmAlias = evmAlias;
        this.futures = requireNonNull(futures);
        this.numCanFail = Math.max(0, numCanFail);

        if (futures.isEmpty()) {
            throw new IllegalArgumentException("The sigs cannot be empty");
        }
    }

    /** {@inheritDoc} */
    @Nullable
    public Bytes evmAlias() {
        return evmAlias;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Key key() {
        return key;
    }

    /**
     * {@inheritDoc}
     *
     * Attempts to cancel all {@link TransactionSignature} futures. Due to the asynchronous nature of those objects,
     * it is possible that the future has not yet even been created, and we do not currently have any way to prevent
     * it from creating a future. The best we can do is to wait for the future to be created and then cancel it.
     * However, waiting for the future is a blocking operation, so this call may block for some time, which is not
     * very nice.
     *
     * <p>Alternatively, we can just do a "best effort" cancel, which is what we will do. If the future exists, we will
     * cancel it. If it does not exist, we will let it be.
     */
    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
        // If we're already done, then we can't cancel again
        if (isDone()) {
            return false;
        }

        // Try to cancel each underlying future (I go ahead and try canceling all of them, even if one fails).
        // If the underlying Future doesn't exist, then we just skip it. Best effort.
        var wasAbleToCancelAll = true;
        for (final var future : futures) {
            if (!future.isDone() && !future.cancel(mayInterruptIfRunning)) {
                wasAbleToCancelAll = false;
            }
        }

        // Record that we have had "canceled" called already, so we don't do it again, and so that "done" is right.
        canceled = true;
        return wasAbleToCancelAll;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCancelled() {
        return canceled;
    }

    /**
     * {@inheritDoc}
     *
     * Due to the asynchronous nature of {@link TransactionSignature}, it is possible that at the time this method
     * is called, one or more {@link TransactionSignature}s do not yet have a {@link Future}. If that is the case,
     * then we are clearly not done!
     */
    @Override
    public boolean isDone() {
        if (canceled) {
            return true;
        }

        for (final var future : futures) {
            if (!future.isDone()) {
                return false;
            }
        }

        return true;
    }

    /**
     * {@inheritDoc}
     *
     * Blocks on each {@link Future}. Due to the asynchronous nature of {@link TransactionSignature}, it is possible
     * that a {@link Future} has not been assigned to one or more {@link TransactionSignature}s. In that case, we will
     * wait for the {@link Future} before proceeding.
     */
    @Override
    public SignatureVerification get() throws InterruptedException, ExecutionException {
        int failCount = 0;
        for (final var future : futures) {
            final var verification = future.get();
            if (!verification.passed() && (failCount++ >= numCanFail)) {
                return new SignatureVerificationImpl(key, evmAlias, false);
            }
        }

        return new SignatureVerificationImpl(key, evmAlias, true);
    }

    /**
     * {@inheritDoc}
     *
     * Blocks on each {@link Future}. Due to the asynchronous nature of {@link TransactionSignature}, it is possible
     * that a {@link Future} has not been assigned to one or more {@link TransactionSignature}s. In that case, we will
     * wait for the {@link Future} before proceeding (up to the timeout).
     */
    @Override
    public SignatureVerification get(final long timeout, final TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        int failCount = 0;
        final var deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        for (final var future : futures) {
            // We know the maximum number of millis we can wait, so we block for at most that long. If it takes
            // longer, the future we delegate to here will throw. If it takes less, we recompute how much is
            // remaining on the next iteration.
            final var now = System.currentTimeMillis();
            final var verification = future.get(deadline - now, TimeUnit.MILLISECONDS);
            if (verification.failed() && (failCount++ >= numCanFail)) {
                return new SignatureVerificationImpl(key, evmAlias, false);
            }
        }

        return new SignatureVerificationImpl(key, evmAlias, true);
    }
}
