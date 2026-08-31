// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.TimeUnit;

/**
 * A {@link SignatureVerificationFuture} that is already complete. Used when ingest verified a payer key
 * and pre-handle should reuse that result instead of calling libsodium again.
 */
public final class CompletedSignatureVerificationFuture implements SignatureVerificationFuture {
    private final Key key;
    private final Bytes evmAlias;
    private final SignatureVerification verification;

    public CompletedSignatureVerificationFuture(
            @NonNull final Key key, @Nullable final Bytes evmAlias, final boolean passed) {
        this.key = requireNonNull(key);
        this.evmAlias = evmAlias;
        this.verification = new SignatureVerificationImpl(key, evmAlias, passed);
    }

    @Nullable
    @Override
    public Bytes evmAlias() {
        return evmAlias;
    }

    @NonNull
    @Override
    public Key key() {
        return key;
    }

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isDone() {
        return true;
    }

    @NonNull
    @Override
    public SignatureVerification get() {
        return verification;
    }

    @NonNull
    @Override
    public SignatureVerification get(final long timeout, @NonNull final TimeUnit unit) {
        return verification;
    }
}
