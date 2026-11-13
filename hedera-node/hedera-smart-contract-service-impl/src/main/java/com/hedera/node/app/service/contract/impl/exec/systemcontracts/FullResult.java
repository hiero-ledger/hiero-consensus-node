// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

/**
 * Represents the result of executing a Hedera system contract.
 *
 * @param result the result of the computation
 * @param gasRequirement the gas requirement of the computation
 * @param recordBuilder the record builder, if any, generated as a side effect of the computation
 * @param workGas the part of the gas requirement that reflects work rather than a fee, which is all that counts
 *     toward the ops-duration throttle; the whole gas requirement unless the result is {@link #pricedAt(long)
 *     priced} above its work
 */
public record FullResult(
        @NonNull PrecompiledContract.PrecompileContractResult result,
        long gasRequirement,
        @Nullable ContractCallStreamBuilder recordBuilder,
        long workGas) {
    public FullResult {
        requireNonNull(result);
    }

    /**
     * Creates a result whose whole gas requirement reflects work.
     *
     * @param result the result of the computation
     * @param gasRequirement the gas requirement of the computation
     * @param recordBuilder the record builder, if any, generated as a side effect of the computation
     */
    public FullResult(
            @NonNull final PrecompiledContract.PrecompileContractResult result,
            final long gasRequirement,
            @Nullable final ContractCallStreamBuilder recordBuilder) {
        this(result, gasRequirement, recordBuilder, gasRequirement);
    }

    /**
     * Returns this result with the given gas requirement, which includes a fee collected through gas on top of
     * the work this result already reports. Only that work counts toward the ops-duration throttle, whether or
     * not the frame can afford the new requirement.
     *
     * @param pricedGasRequirement the gas requirement including the fee
     * @return the priced result
     */
    public FullResult pricedAt(final long pricedGasRequirement) {
        return new FullResult(result, pricedGasRequirement, recordBuilder, workGas);
    }

    public Bytes output() {
        return result.output();
    }

    public boolean isRefundGas() {
        return result.isRefundGas();
    }

    public static FullResult ordinalRevertResult(@NonNull final ResponseCodeEnum reason, final long gasRequirement) {
        requireNonNull(reason);
        return new FullResult(
                PrecompiledContract.PrecompileContractResult.revert(Bytes.wrap(UInt256.valueOf(reason.protoOrdinal()))),
                gasRequirement,
                null);
    }

    public static FullResult revertResult(@NonNull final ResponseCodeEnum reason, final long gasRequirement) {
        requireNonNull(reason);
        return new FullResult(
                PrecompiledContract.PrecompileContractResult.revert(
                        // Future change reason.protoName() with reason.protoOrdinal()
                        Bytes.wrap(reason.protoName().getBytes())),
                gasRequirement,
                null);
    }

    public static FullResult revertResult(@NonNull final Bytes reason, final long gasRequirement) {
        requireNonNull(reason);
        return new FullResult(PrecompiledContract.PrecompileContractResult.revert(reason), gasRequirement, null);
    }

    public static FullResult revertResult(
            @NonNull final ContractCallStreamBuilder recordBuilder, final long gasRequirement) {
        requireNonNull(recordBuilder);
        return new FullResult(
                PrecompiledContract.PrecompileContractResult.revert(
                        // match mono - return status ordinal instead of name
                        Bytes.wrap(UInt256.valueOf(recordBuilder.status().protoOrdinal()))),
                gasRequirement,
                recordBuilder);
    }

    public static FullResult haltResult(
            @NonNull final ContractCallStreamBuilder recordBuilder, final long gasRequirement) {
        requireNonNull(recordBuilder);
        final var reason = recordBuilder.status() == NOT_SUPPORTED
                ? CustomExceptionalHaltReason.NOT_SUPPORTED
                : ERROR_DECODING_PRECOMPILE_INPUT;
        return new FullResult(
                PrecompiledContract.PrecompileContractResult.halt(Bytes.EMPTY, Optional.of(reason)),
                gasRequirement,
                recordBuilder);
    }

    public static FullResult haltResult(@NonNull final ExceptionalHaltReason reason, final long gasRequirement) {
        requireNonNull(reason);
        return new FullResult(
                PrecompiledContract.PrecompileContractResult.halt(Bytes.EMPTY, Optional.of(reason)),
                gasRequirement,
                null);
    }

    public static FullResult successResult(
            @NonNull final ByteBuffer encoded,
            final long gasRequirement,
            @NonNull final ContractCallStreamBuilder recordBuilder) {
        requireNonNull(encoded);
        return new FullResult(
                PrecompiledContract.PrecompileContractResult.success(Bytes.wrap(encoded.array())),
                gasRequirement,
                recordBuilder);
    }

    public static FullResult successResult(@NonNull final ByteBuffer encoded, final long gasRequirement) {
        requireNonNull(encoded);
        return new FullResult(
                PrecompiledContract.PrecompileContractResult.success(Bytes.wrap(encoded.array())),
                gasRequirement,
                null);
    }

    public static FullResult successResult(@NonNull final Bytes encoded, final long gasRequirement) {
        requireNonNull(encoded);
        return new FullResult(PrecompiledContract.PrecompileContractResult.success(encoded), gasRequirement, null);
    }

    public static FullResult haltResult(final long gasRequirement) {
        return new FullResult(
                PrecompiledContract.PrecompileContractResult.halt(
                        Bytes.EMPTY, Optional.of(ERROR_DECODING_PRECOMPILE_INPUT)),
                gasRequirement,
                null);
    }
}
