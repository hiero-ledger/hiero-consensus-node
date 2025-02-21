// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.signatures;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * During the "pre-handle" phase of a transaction, the node will perform signature verification on the transaction.
 * Each {@link com.hedera.node.app.spi.workflows.TransactionHandler} has a chance to "gather" required non-payer
 * keys that must be used for signing. The application will then take these and perform asynchronous signature
 * verification. The result of this will be made available to the transaction handler during the "handle" phase
 * as a {@link SignatureVerification}.
 */
public interface SignatureVerification {
    /**
     * Gets the key that was used with this signature check.
     *
     * <p>In most cases, the service implementation's {@link com.hedera.node.app.spi.workflows.TransactionHandler}s
     * will require signatures based on some concrete key. In those cases, they will be made available here. In the case
     * of a "hollow" account, where the key is not known, the handler will provide the hollow {@link Account}. The
     * signature checking code will look for a corresponding signature. If it finds one, it will extract the key and
     * make it available here. If it cannot find a corresponding signature, then this key will be null.
     *
     * @return The key that was used with this signature check, or null if the key is not known.
     */
    @Nullable
    Key key();

    /**
     * Gets the EVM alias for the {@link #key()}, if, and only if, the key is an ECDSA_SECP256K1 key.
     *
     * @return The evm alias, or null the key is not of the correct type.
     */
    @Nullable
    default Bytes evmAlias() {
        return null;
    }

    /**
     * Gets whether this signature was verified.
     *
     * @return {@code true} if the signature was verified, {@code false} otherwise
     */
    boolean passed();

    /**
     * Gets whether this signature was not verified.
     *
     * @return {@code false} if the signature was verified, {@code true} otherwise
     */
    default boolean failed() {
        return !passed();
    }
}
