// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.concurrent.Future;
import org.hiero.base.crypto.TransactionSignature;

/**
 * A {@link Future} that waits on a {@link Map} of {@link TransactionSignature}s to complete signature checks, and
 * yields a {@link SignatureVerification}.
 */
public interface SignatureVerificationFuture extends Future<SignatureVerification> {
    /**
     * Gets the EVM Alias for the key. If the key is an ECDSA (secp256k1) key, then this may be set. Otherwise, it
     * will be null.
     *
     * @return The evm alias, if any.
     */
    @Nullable
    Bytes evmAlias();

    /**
     * Gets the key that will be present on the resulting {@link SignatureVerification}.
     * @return The key
     */
    @NonNull
    Key key();
}
