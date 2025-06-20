// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.base.crypto.VerificationStatus.VALID;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hiero.base.crypto.TransactionSignature;

/**
 * A {@link Future} that waits on a {@link TransactionSignature} to complete signature checks, and yields a
 * {@link SignatureVerification}.
 */
public final class SignatureVerificationFutureImpl implements SignatureVerificationFuture {
    /**
     * The Key we verified. This will *never* be null, because we would not have attempted signature verification
     * without having a key. If an EVM address was used, we would have already extracted the key, so it can be
     * provided here.
     */
    private final Key key;
    /**
     * Optional: used only with ECDSA_SECP256K1 keys, the EVM address associated with the key.
     */
    private final Bytes evmAlias;
    /**
     * The {@link TransactionSignature} that holds the {@link Future} used to determine when the cryptographic
     * signature check is complete,
     */
    private final TransactionSignature txSig;
    /**
     * Whether *this* future has been canceled. Used for properly implementing {@link Future} semantics.
     */
    private boolean canceled = false;

    /**
     * Create a new instance.
     *
     * @param key The key associated with this sig check. Cannot be null.
     * @param evmAlias The evm address alias, if any (always set if the key is an ECDSA_SECP256K1 key)
     * @param txSig The {@link TransactionSignature}s, from which the pass/fail status of the
     * {@link SignatureVerification} is derived. This list must contain at least one element.
     */
    public SignatureVerificationFutureImpl(
            @NonNull final Key key, @Nullable final Bytes evmAlias, @NonNull final TransactionSignature txSig) {
        this.key = requireNonNull(key);
        this.evmAlias = evmAlias;
        this.txSig = requireNonNull(txSig);
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes evmAlias() {
        return evmAlias;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Key key() {
        return key;
    }

    @NonNull
    public TransactionSignature txSig() {
        return txSig;
    }

    /**
     * {@inheritDoc}
     *
     * Attempts to cancel the {@link TransactionSignature} future. Due to the asynchronous nature of those objects,
     * it is possible that the future has not yet even been created, and we do not currently have any way to prevent
     * it from creating a future. The best we can do is to wait for the future to be created and then cancel it.
     * However, waiting for the future is a blocking operation, so this would block for some time, which is not
     * very nice.
     *
     * <p>instead, we can just do a "best effort" cancel, which is what we will do. If the future exists, we will
     * cancel it. If it does not exist, we will let it be.
     */
    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
        // If we're already done, then we can't cancel again
        if (isDone()) {
            return false;
        }

        // Try to cancel the underlying future, if it exists. If the underlying Future doesn't exist, then we just skip
        // it. Best effort.
        canceled = true;
        return true;
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
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * Blocks on each {@link Future}. Due to the asynchronous nature of {@link TransactionSignature}, it is possible
     * that a {@link Future} has not been assigned to one or more {@link TransactionSignature}s. In that case, we will
     * wait for the {@link Future} before proceeding.
     */
    @NonNull
    @Override
    public SignatureVerification get() throws InterruptedException, ExecutionException {
        return new SignatureVerificationImpl(key, evmAlias, txSig.getSignatureStatus() == VALID);
    }

    /**
     * {@inheritDoc}
     *
     * Blocks on each {@link Future}. Due to the asynchronous nature of {@link TransactionSignature}, it is possible
     * that a {@link Future} has not been assigned to one or more {@link TransactionSignature}s. In that case, we will
     * wait for the {@link Future} before proceeding (up to the timeout).
     */
    @NonNull
    @Override
    public SignatureVerification get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return new SignatureVerificationImpl(key, evmAlias, txSig.getSignatureStatus() == VALID);
    }
}
