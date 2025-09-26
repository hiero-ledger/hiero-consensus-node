// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulecall;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides help in decoding an {@link HssCallAttempt} representing an scheduleCall into a synthetic
 * {@link TransactionBody}.
 */
@Singleton
public class ScheduleCallDecoder {

    /**
     * Default constructor for injection.
     */
    @Inject
    public ScheduleCallDecoder() {
        // Dagger2
    }

    /**
     * @param attempt the HSS call attempt
     * @param keys    the key set for scheduled calls
     * @return the schedule call transaction body
     */
    public TransactionBody decodeScheduleCall(@NonNull final HssCallAttempt attempt, @NonNull final Set<Key> keys) {
        // read parameters
        final Tuple call;
        final Address to;
        final AccountID payer;
        final boolean waitForExpiry;
        int paramIndex = 0;
        if (attempt.isSelector(ScheduleCallTranslator.SCHEDULE_CALL)) {
            call = ScheduleCallTranslator.SCHEDULE_CALL.decodeCall(attempt.inputBytes());
            to = call.get(paramIndex++);
            payer = attempt.senderId();
            waitForExpiry = true;
        } else if (attempt.isSelector(ScheduleCallTranslator.SCHEDULE_CALL_WITH_PAYER)) {
            call = ScheduleCallTranslator.SCHEDULE_CALL_WITH_PAYER.decodeCall(attempt.inputBytes());
            to = call.get(paramIndex++);
            payer = attempt.addressIdConverter().convert(call.get(paramIndex++));
            waitForExpiry = true;
        } else if (attempt.isSelector(ScheduleCallTranslator.EXECUTE_CALL_ON_PAYER_SIGNATURE)) {
            call = ScheduleCallTranslator.EXECUTE_CALL_ON_PAYER_SIGNATURE.decodeCall(attempt.inputBytes());
            to = call.get(paramIndex++);
            payer = attempt.addressIdConverter().convert(call.get(paramIndex++));
            waitForExpiry = false;
        } else {
            throw new IllegalStateException("Unexpected function selector");
        }

        final long expirySecond = ConversionUtils.asLongLimitedToZeroOrMax(call.get(paramIndex++));
        final long gasLimit = ConversionUtils.asLongLimitedToZeroOrMax(call.get(paramIndex++));
        final long value = ConversionUtils.asLongLimitedToZeroOrMax(call.get(paramIndex++));
        final byte[] callData = call.get(paramIndex);

        // convert parameters
        final var contractId = ConversionUtils.asContractId(
                attempt.enhancement().nativeOperations().entityIdFactory(), ConversionUtils.fromHeadlongAddress(to));

        return transactionBodyFor(
                attempt,
                scheduleCreateTransactionBodyFor(
                        scheduledTransactionBodyFor(contractId, gasLimit, value, callData),
                        keys,
                        expirySecond,
                        payer,
                        waitForExpiry));
    }

    /**
     * Creates a transaction body for:
     * <br>
     * - {@code scheduleCall(address,uint256,uint256,uint64,bytes)}
     * <br>
     * - {@code scheduleCallWithPayer(address,address,uint256,uint256,uint64,bytes)}
     * <br>
     * - {@code executeCallOnPayerSignature(address,address,uint256,uint256,uint64,bytes)}
     *
     * @param attempt           the HSS call attempt
     * @param scheduleCreateTrx the 'schedule create' transaction body
     * @return the transaction body
     */
    @VisibleForTesting
    public TransactionBody transactionBodyFor(
            @NonNull final HssCallAttempt attempt, @NonNull final ScheduleCreateTransactionBody scheduleCreateTrx) {
        return TransactionBody.newBuilder()
                // passing current TransactionID to HSS via child call for actual schedule creation
                .transactionID(attempt.enhancement().nativeOperations().getTransactionID())
                // create ScheduleCreateTransactionBody
                .scheduleCreate(scheduleCreateTrx)
                .build();
    }

    /**
     * Creates a schedule create transaction body for:
     * <br>
     * - {@code scheduleCall(address,uint256,uint256,uint64,bytes)}
     * <br>
     * - {@code scheduleCallWithPayer(address,address,uint256,uint256,uint64,bytes)}
     * <br>
     * - {@code executeCallOnPayerSignature(address,address,uint256,uint256,uint64,bytes)}
     *
     * @param scheduleTrx   scheduled transaction body for this schedule create
     * @param keys          the key set for scheduled calls
     * @param expirySecond  an expiration time of the future call
     * @param payer        an account identifier of a `payer` for the scheduled transaction
     * @param waitForExpiry a flag to delay execution until expiration
     * @return the 'schedule create' transaction body
     */
    @VisibleForTesting
    public ScheduleCreateTransactionBody scheduleCreateTransactionBodyFor(
            @NonNull final SchedulableTransactionBody scheduleTrx,
            @NonNull final Set<Key> keys,
            final long expirySecond,
            @NonNull final AccountID payer,
            boolean waitForExpiry) {
        requireNonNull(scheduleTrx);
        requireNonNull(keys);
        requireNonNull(payer);
        return ScheduleCreateTransactionBody.newBuilder()
                .scheduledTransactionBody(scheduleTrx)
                // we need to set adminKey for make schedule not immutable and to be able to delete schedule
                .adminKey(keys.stream().findFirst().orElse(null))
                .expirationTime(Timestamp.newBuilder().seconds(expirySecond))
                .payerAccountID(payer)
                .waitForExpiry(waitForExpiry)
                .build();
    }

    /**
     * Creates a schedule transaction body with contract call for:
     * <br>
     * - {@code scheduleCall(address,uint256,uint256,uint64,bytes)}
     * <br>
     * - {@code scheduleCallWithPayer(address,address,uint256,uint256,uint64,bytes)}
     * <br>
     * - {@code executeCallOnPayerSignature(address,address,uint256,uint256,uint64,bytes)}
     *
     * @param contractId contract id for the future call
     * @param gasLimit   a maximum limit to the amount of gas to use for future call
     * @param value      an amount of tinybar sent via this future contract call
     * @param callData   the smart contract function to call. This MUST contain The application binary interface (ABI)
     *                   encoding of the function call per the Ethereum contract ABI standard, giving the function
     *                   signature and arguments being passed to the function.
     * @return the 'schedule transaction' body with 'contract call'
     */
    @VisibleForTesting
    public SchedulableTransactionBody scheduledTransactionBodyFor(
            @NonNull final ContractID contractId, final long gasLimit, final long value, @NonNull byte[] callData) {
        requireNonNull(contractId);
        requireNonNull(callData);
        return SchedulableTransactionBody.newBuilder()
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .contractID(contractId)
                        .gas(gasLimit)
                        .amount(value)
                        .functionParameters(Bytes.wrap(callData)))
                .build();
    }
}
