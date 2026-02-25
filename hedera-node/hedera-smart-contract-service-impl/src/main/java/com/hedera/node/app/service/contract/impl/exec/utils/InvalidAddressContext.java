// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.datatypes.Address;

/**
 * A class holding additional context information necessary when handling
 * {@link com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason} of type INVALID_SOLIDITY_ADDRESS.
 */
public final class InvalidAddressContext {
    /** Represents a use-case-specific type of the address that was invalid.
     * For the purpose of creating a synthetic ContractAction
     * we need to know whether the address was the target address of a call,
     * or something else (in which case we don't care about specifics).
     * See: {@link ActionStack}.
     */
    public enum InvalidAddressType {
        InvalidCallTarget,
        NonCallTarget,
    }

    private Address culpritAddress = Address.ZERO;
    private InvalidAddressType type = InvalidAddressType.NonCallTarget;

    public void set(@NonNull final Address culpritAddress, @NonNull final InvalidAddressType type) {
        this.culpritAddress = requireNonNull(culpritAddress);
        this.type = requireNonNull(type);
    }

    public Address culpritAddress() {
        return this.culpritAddress;
    }

    public InvalidAddressType type() {
        return this.type;
    }
}
