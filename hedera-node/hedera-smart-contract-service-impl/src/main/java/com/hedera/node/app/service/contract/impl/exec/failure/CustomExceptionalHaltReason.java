// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.failure;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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

    private static final Map<ExceptionalHaltReason, ResponseCodeEnum> HALT_REASON_TO_STATUS;

    static {
        Map<ExceptionalHaltReason, ResponseCodeEnum> map = new HashMap<>();
        map.put(SELF_DESTRUCT_TO_SELF, ResponseCodeEnum.OBTAINER_SAME_CONTRACT_ID);
        map.put(INVALID_SOLIDITY_ADDRESS, ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS);
        map.put(INVALID_ALIAS_KEY, ResponseCodeEnum.INVALID_ALIAS_KEY);
        map.put(INVALID_SIGNATURE, ResponseCodeEnum.INVALID_SIGNATURE);
        map.put(CONTRACT_ENTITY_LIMIT_REACHED, ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);
        map.put(INSUFFICIENT_CHILD_RECORDS, ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED);
        map.put(INVALID_CONTRACT_ID, ResponseCodeEnum.INVALID_CONTRACT_ID);
        map.put(INVALID_FEE_SUBMITTED, ResponseCodeEnum.INVALID_FEE_SUBMITTED);
        map.put(INSUFFICIENT_GAS, ResponseCodeEnum.INSUFFICIENT_GAS);
        map.put(ILLEGAL_STATE_CHANGE, ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION);
        HALT_REASON_TO_STATUS = Collections.unmodifiableMap(map);
    }

    /**
     * Returns the "preferred" status for the given halt reason.
     *
     * @param reason the halt reason
     * @return the status
     */
    public static ResponseCodeEnum statusFor(@NonNull final ExceptionalHaltReason reason) {
        requireNonNull(reason);
        return HALT_REASON_TO_STATUS.getOrDefault(reason, ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION);
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
