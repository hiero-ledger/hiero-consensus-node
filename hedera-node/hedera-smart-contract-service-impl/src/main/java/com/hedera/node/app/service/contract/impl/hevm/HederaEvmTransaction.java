// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.hooks.HookDispatchTransactionBody;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

public record HederaEvmTransaction(
        @NonNull AccountID senderId,
        @Nullable AccountID relayerId,
        @Nullable ContractID contractId,
        long nonce,
        @NonNull Bytes payload,
        @Nullable Bytes chainId,
        long value,
        long gasLimit,
        long offeredGasPrice,
        long maxGasAllowance,
        @Nullable ContractCreateTransactionBody hapiCreation,
        @Nullable HandleException exception,
        @Nullable HookDispatchTransactionBody hookDispatch) {
    public static final long NOT_APPLICABLE = -1L;

    public boolean hasExpectedNonce() {
        return nonce != NOT_APPLICABLE;
    }

    public boolean hasOfferedGasPrice() {
        return offeredGasPrice != NOT_APPLICABLE;
    }

    public boolean hasMaxGasAllowance() {
        return maxGasAllowance != NOT_APPLICABLE;
    }

    public boolean isCreate() {
        return contractId == null;
    }

    public boolean needsInitcodeExternalizedOnFailure() {
        return hapiCreation != null && !hapiCreation.hasInitcode();
    }

    public boolean isEthereumTransaction() {
        return relayerId != null;
    }

    public boolean isHookExecution() {
        return hookDispatch != null;
    }

    public boolean isContractCall() {
        return !isEthereumTransaction() && !isCreate();
    }

    public boolean isException() {
        return exception != null;
    }

    public boolean permitsMissingContract() {
        return isEthereumTransaction() && hasValue();
    }

    public @NonNull ContractID contractIdOrThrow() {
        return requireNonNull(contractId);
    }

    public boolean hasValue() {
        return value > 0;
    }

    public org.apache.tuweni.bytes.Bytes evmPayload() {
        return pbjToTuweniBytes(payload);
    }

    public Wei weiValue() {
        return Wei.of(value);
    }

    public long gasAvailable(final long intrinsicGas) {
        return gasLimit - intrinsicGas;
    }

    public long upfrontCostGiven(final long gasPrice) {
        final var gasCost = gasCostGiven(gasPrice);
        return gasCost == Long.MAX_VALUE ? Long.MAX_VALUE : gasCost + value;
    }

    public long unusedGas(final long gasUsed) {
        return gasLimit - gasUsed;
    }

    public long gasCostGiven(final long gasPrice) {
        try {
            return Math.multiplyExact(gasLimit, gasPrice);
        } catch (ArithmeticException ignore) {
            return Long.MAX_VALUE;
        }
    }

    public long offeredGasCost() {
        try {
            return Math.multiplyExact(gasLimit, offeredGasPrice);
        } catch (ArithmeticException ignore) {
            return Long.MAX_VALUE;
        }
    }

    public boolean requiresFullRelayerAllowance() {
        return offeredGasPrice == 0L;
    }

    /**
     * @param exception the exception to set
     * @return a copy of this transaction with the given {@code exception}
     */
    public HederaEvmTransaction withException(@NonNull final HandleException exception) {
        return new HederaEvmTransaction(
                this.senderId,
                this.relayerId,
                this.contractId,
                this.nonce,
                this.payload,
                this.chainId,
                this.value,
                this.gasLimit,
                this.offeredGasPrice,
                this.maxGasAllowance,
                this.hapiCreation,
                exception,
                this.hookDispatch);
    }
    /**
     * @return the hook id, or null if this is not a hook dispatch
     */
    @Nullable
    public HookId maybeHookId() {
        return hookDispatch != null
                ? new HookId(
                        hookDispatch.executionOrThrow().hookEntityIdOrThrow(),
                        hookDispatch.executionOrThrow().callOrThrow().hookIdOrThrow())
                : null;
    }

    /**
     * @return the address of the hook owner, or null if this is not a hook dispatch
     */
    public Address hookOwnerAddress(@NonNull final HederaWorldUpdater worldUpdater) {
        requireNonNull(worldUpdater);
        if (hookDispatch == null) {
            return null;
        }
        final var entityId = hookDispatch.executionOrThrow().hookEntityIdOrThrow();
        return entityId.hasContractId()
                ? requireNonNull(worldUpdater.getHederaAccount(entityId.contractIdOrThrow()))
                        .getAddress()
                : requireNonNull(worldUpdater.getHederaAccount(entityId.accountIdOrThrow()))
                        .getAddress();
    }
}
