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
    private Address culpritAddress = Address.ZERO;
    private boolean raisedDueToInvalidCallTarget = false;

    public void set(@NonNull final Address culpritAddress, final boolean raisedDueToInvalidCallTarget) {
        this.culpritAddress = requireNonNull(culpritAddress);
        this.raisedDueToInvalidCallTarget = raisedDueToInvalidCallTarget;
    }

    public Address culpritAddress() {
        return this.culpritAddress;
    }

    public boolean raisedDueToInvalidCallTarget() {
        return this.raisedDueToInvalidCallTarget;
    }
}
