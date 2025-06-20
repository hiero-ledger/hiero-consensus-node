// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * An implementation of {@link SignatureVerification}.
 *
 * @param key The key, if any
 * @param evmAlias The evm alias of the key if supplied.
 * @param passed Whether the verification passed (VALID) or not.
 */
public record SignatureVerificationImpl(@Nullable Key key, @Nullable Bytes evmAlias, boolean passed)
        implements SignatureVerification {

    public static SignatureVerification passedVerification(@NonNull final Key key) {
        return new SignatureVerificationImpl(key, null, true);
    }

    public static SignatureVerification failedVerification(@NonNull final Key key) {
        return new SignatureVerificationImpl(key, null, false);
    }

    public static SignatureVerification passedVerification(@NonNull final Bytes evmAlias) {
        return new SignatureVerificationImpl(null, evmAlias, true);
    }

    public static SignatureVerification failedVerification(@NonNull final Bytes evmAlias) {
        return new SignatureVerificationImpl(null, evmAlias, false);
    }
}
