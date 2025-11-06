// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static java.util.Objects.requireNonNull;

import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.contract.InternalCallContext;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Utilities for system contracts.
 */
public final class SystemContractUtils {
    public static final String HTS_PRECOMPILED_CONTRACT_ADDRESS = "0x167";
    public static final ContractID HTS_PRECOMPILE_MIRROR_ID = contractIdFromEvmAddress(
            Address.fromHexString(HTS_PRECOMPILED_CONTRACT_ADDRESS).toArrayUnsafe());

    private SystemContractUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public enum ResultStatus {
        IS_SUCCESS,
        IS_ERROR
    }

    /**
     * Create a successful contract function result.
     * @param gasUsed Report the gas used.
     * @param result The result of the contract call.
     * @param gas The remaining gas.
     * @param inputData The input data.
     * @param senderId The sender id.
     * @return The created contract function result for a successful call.
     */
    @NonNull
    public static ContractFunctionResult successResultOfZeroValueTraceable(
            final long gasUsed,
            final Bytes result,
            final long gas,
            @NonNull final Bytes inputData,
            @NonNull final AccountID senderId) {
        return ContractFunctionResult.newBuilder()
                .gasUsed(gasUsed)
                .gas(gas)
                .contractCallResult(tuweniToPbjBytes(result))
                .functionParameters(tuweniToPbjBytes(inputData))
                .senderId(senderId)
                .contractID(HTS_PRECOMPILE_MIRROR_ID)
                .build();
    }

    /**
     * Create a successful contract function result.
     * @param gasUsed Report the gas used.
     * @param result The result of the contract call.
     * @param gas The remaining gas.
     * @param inputData The input data.
     * @param senderId The sender id.
     * @return The created contract function result for a successful call.
     */
    @NonNull
    public static EvmTransactionResult txSuccessResultOfZeroValueTraceable(
            final long gasUsed,
            final Bytes result,
            final long gas,
            @NonNull final Bytes inputData,
            @NonNull final AccountID senderId) {
        return EvmTransactionResult.newBuilder()
                .internalCallContext(InternalCallContext.newBuilder().gas(gas).callData(tuweniToPbjBytes(inputData)))
                .gasUsed(gasUsed)
                .resultData(tuweniToPbjBytes(result))
                .senderId(senderId)
                .contractId(HTS_PRECOMPILE_MIRROR_ID)
                .build();
    }

    /**
     * Create a successful contract function result for the given frame with
     * the known sender and result.
     *
     * @param senderId the sender id
     * @param fullResult the full result
     * @param frame the frame
     * @param includeTraceabilityFields whether to include traceability fields
     * @return the created contract function result for a successful call
     */
    public static @NonNull ContractFunctionResult successResultOf(
            @NonNull final AccountID senderId,
            @NonNull final FullResult fullResult,
            @NonNull final MessageFrame frame,
            final boolean includeTraceabilityFields) {
        requireNonNull(senderId);
        requireNonNull(fullResult);
        requireNonNull(frame);
        final var builder = ContractFunctionResult.newBuilder()
                .gasUsed(fullResult.gasRequirement())
                .contractCallResult(tuweniToPbjBytes(fullResult.result().getOutput()))
                .senderId(senderId)
                .contractID(HTS_PRECOMPILE_MIRROR_ID);
        if (includeTraceabilityFields) {
            builder.gas(frame.getRemainingGas())
                    .amount(frame.getValue().toLong())
                    .functionParameters(tuweniToPbjBytes(frame.getInputData()));
        }
        return builder.build();
    }

    /**
     * Create a successful contract function result for the given frame with
     * the known sender and result.
     *
     * @param senderId the sender id
     * @param fullResult the full result
     * @param frame the frame
     * @param includeTraceabilityFields whether to include traceability fields
     * @return the created contract function result for a successful call
     */
    public static @NonNull EvmTransactionResult txSuccessResultOf(
            @NonNull final AccountID senderId,
            @NonNull final FullResult fullResult,
            @NonNull final MessageFrame frame,
            final boolean includeTraceabilityFields) {
        requireNonNull(senderId);
        requireNonNull(fullResult);
        requireNonNull(frame);
        final var builder = EvmTransactionResult.newBuilder()
                .gasUsed(fullResult.gasRequirement())
                .resultData(tuweniToPbjBytes(fullResult.result().getOutput()))
                .senderId(senderId)
                .contractId(HTS_PRECOMPILE_MIRROR_ID);
        if (includeTraceabilityFields) {
            builder.internalCallContext(InternalCallContext.newBuilder()
                    .gas(frame.getRemainingGas())
                    .value(frame.getValue().toLong())
                    .callData(tuweniToPbjBytes(frame.getInputData())));
        }
        return builder.build();
    }

    /**
     * Create an error contract function result.
     *
     * @param fullResult The result of the failed contract call
     * @param errorMsg The error message to report back to the caller.
     * @param contractID The contract ID.
     * @return The created contract function result when for a failed call.
     */
    public static @NonNull ContractFunctionResult contractFunctionResultFailedFor(
            @NonNull final AccountID senderId,
            @NonNull final FullResult fullResult,
            final String errorMsg,
            final ContractID contractID) {
        return contractFunctionResultFailedFor(
                senderId, fullResult.result().getOutput(), fullResult.gasRequirement(), errorMsg, contractID);
    }

    public static @NonNull ContractFunctionResult contractFunctionResultFailedFor(
            @NonNull final AccountID senderId,
            @NonNull final Bytes result,
            final long gasRequirement,
            final String errorMsg,
            final ContractID contractID) {
        return ContractFunctionResult.newBuilder()
                .gasUsed(gasRequirement)
                .contractCallResult(tuweniToPbjBytes(result))
                .senderId(senderId)
                .errorMessage(errorMsg)
                .contractID(contractID)
                .build();
    }

    public static @NonNull EvmTransactionResult txResultFailedFor(
            @NonNull final AccountID senderId,
            @NonNull final Bytes result,
            final long gasRequirement,
            final String errorMsg,
            final ContractID contractID) {
        return EvmTransactionResult.newBuilder()
                .gasUsed(gasRequirement)
                .resultData(tuweniToPbjBytes(result))
                .senderId(senderId)
                .errorMessage(errorMsg)
                .contractId(contractID)
                .build();
    }

    private static ContractID contractIdFromEvmAddress(final byte[] bytes) {
        return ContractID.newBuilder()
                .contractNum(Longs.fromByteArray(Arrays.copyOfRange(bytes, 12, 20)))
                .build();
    }

    /**
     * compute the message as the concatenation of the realm, shard, and schedule numbers
     * @param scheduleId the schedule id
     * @return the message as defined above encoded as Bytes
     */
    public static com.hedera.pbj.runtime.io.buffer.Bytes messageFromScheduleId(final ScheduleID scheduleId) {
        final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 3);
        buffer.putLong(scheduleId.shardNum());
        buffer.putLong(scheduleId.realmNum());
        buffer.putLong(scheduleId.scheduleNum());
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(buffer.array());
    }
}
