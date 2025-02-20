// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.failure;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;

/**
 * Enum which implements {@link org.hyperledger.besu.evm.frame.ExceptionalHaltReason} and enhances it with more halt reasons.
 */
@SuppressWarnings("ALL")
public enum CustomExceptionalHaltReason implements ExceptionalHaltReason {
    INVALID_CONTRACT_ID("Invalid contract id"),
    INVALID_SOLIDITY_ADDRESS("Invalid account reference"),
    INVALID_ALIAS_KEY("Invalid alias key"),
    SELF_DESTRUCT_TO_SELF("Self destruct to the same address"),
    CONTRACT_IS_TREASURY("Token treasuries cannot be deleted"),
    INVALID_SIGNATURE("Invalid signature"),
    TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES("Accounts with positive fungible token balances cannot be deleted"),
    CONTRACT_STILL_OWNS_NFTS("Accounts who own nfts cannot be deleted"),
    ERROR_DECODING_PRECOMPILE_INPUT("Error when decoding precompile input."),
    FAILURE_DURING_LAZY_ACCOUNT_CREATION("Failure during lazy account creation"),
    NOT_SUPPORTED("Not supported."),
    CONTRACT_ENTITY_LIMIT_REACHED("Contract entity limit reached."),
    INVALID_FEE_SUBMITTED("Invalid fee submitted for an EVM call."),
    INSUFFICIENT_CHILD_RECORDS("Result cannot be externalized due to insufficient child records");

    private final String description;

    CustomExceptionalHaltReason(@NonNull final String description) {
        this.description = description;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /**
     * Returns the "preferred" status for the given halt reason.
     *
     * @param reason the halt reason
     * @return the status
     */
    // FUTURE: refactor in the future to be more readable when we start looking for cleanups
    // Future cannot be addressed until gradle update per
    // https://github.com/autonomousapps/dependency-analysis-gradle-plugin/issues/1150
    public static ResponseCodeEnum statusFor(@NonNull final ExceptionalHaltReason reason) {
        requireNonNull(reason);
        if (reason == SELF_DESTRUCT_TO_SELF) {
            return ResponseCodeEnum.OBTAINER_SAME_CONTRACT_ID;
        } else if (reason == INVALID_SOLIDITY_ADDRESS) {
            return ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
        } else if (reason == INVALID_ALIAS_KEY) {
            return ResponseCodeEnum.INVALID_ALIAS_KEY;
        } else if (reason == INVALID_SIGNATURE) {
            return ResponseCodeEnum.INVALID_SIGNATURE;
        } else if (reason == CONTRACT_ENTITY_LIMIT_REACHED) {
            return ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
        } else if (reason == ExceptionalHaltReason.INSUFFICIENT_GAS) {
            return ResponseCodeEnum.INSUFFICIENT_GAS;
        } else if (reason == ExceptionalHaltReason.ILLEGAL_STATE_CHANGE) {
            return ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION;
        } else if (reason == CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS) {
            return ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
        } else if (reason == CustomExceptionalHaltReason.INVALID_CONTRACT_ID) {
            return ResponseCodeEnum.INVALID_CONTRACT_ID;
        } else if (reason == CustomExceptionalHaltReason.INVALID_FEE_SUBMITTED) {
            return ResponseCodeEnum.INVALID_FEE_SUBMITTED;
        } else {
            return ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;
        }
    }

    public static String errorMessageFor(@NonNull final ExceptionalHaltReason reason) {
        requireNonNull(reason);
        // #10568 - We add this check to match mono behavior
        if (reason == CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS) {
            return Bytes.of(ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED.name().getBytes())
                    .toHexString();
        }
        return reason.toString();
    }
}
