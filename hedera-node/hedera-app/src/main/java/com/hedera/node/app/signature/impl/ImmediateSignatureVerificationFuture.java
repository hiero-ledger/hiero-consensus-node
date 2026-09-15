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

/** A completed signature-verification future for synchronous algorithms. */
final class ImmediateSignatureVerificationFuture implements SignatureVerificationFuture {
    private final Key key;
    private final boolean passed;

    ImmediateSignatureVerificationFuture(@NonNull final Key key, final boolean passed) {
        this.key = requireNonNull(key);
        this.passed = passed;
    }

    @Override
    public @Nullable Bytes evmAlias() {
        return null;
    }

    @Override
    public @NonNull Key key() {
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

    @Override
    public @NonNull SignatureVerification get() {
        return new SignatureVerificationImpl(key, null, passed);
    }

    @Override
    public @NonNull SignatureVerification get(final long timeout, @NonNull final TimeUnit unit) {
        requireNonNull(unit);
        return get();
    }
}
