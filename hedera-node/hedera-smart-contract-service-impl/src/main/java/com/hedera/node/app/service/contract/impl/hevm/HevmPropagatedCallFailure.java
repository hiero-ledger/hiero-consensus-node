// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;

/**
 * Enumerates special types of call failures in the Hedera EVM that not only prevent the frame
 * of the call from being processed, but also halt the execution of the parent frame whose code
 * contained the initiating {@code CALL} operation.
 */
public enum HevmPropagatedCallFailure {
    /**
     * No special failure occurred.
     */
    NONE(null),
    /**
     * The call failed due to a missing signature on the receiver account.
     */
    MISSING_RECEIVER_SIGNATURE(CustomExceptionalHaltReason.INVALID_SIGNATURE),
    /**
     * The call failed because its externalizing its result would exceed the maximum number of child records.
     */
    RESULT_CANNOT_BE_EXTERNALIZED(CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS),
    /**
     * The call failed because invalid fee was submitted for an EVM call
     */
    INVALID_FEE_SUBMITTED(CustomExceptionalHaltReason.INVALID_FEE_SUBMITTED),
    /**
     * The call failed because the contract id submitted for an EVM call was invalid
     */
    INVALID_CONTRACT_ID(CustomExceptionalHaltReason.INVALID_CONTRACT_ID);

    private final @Nullable CustomExceptionalHaltReason exceptionalHaltReason;

    HevmPropagatedCallFailure(@Nullable final CustomExceptionalHaltReason exceptionalHaltReason) {
        this.exceptionalHaltReason = exceptionalHaltReason;
    }

    /**
     * Returns the {@link ExceptionalHaltReason} that should be used to halt the parent frame, if any.
     *
     * @return the halt reason, if any
     */
    public Optional<ExceptionalHaltReason> exceptionalHaltReason() {
        return Optional.ofNullable(exceptionalHaltReason);
    }
}
