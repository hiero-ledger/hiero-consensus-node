// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadataRef;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.spi.workflows.ContractGasUsage;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.evm.frame.BlockValues;

public record HederaEvmContext(
        long gasPrice, // weibar
        boolean staticCall,
        boolean shouldChargeGasFees,
        @NonNull HederaEvmBlocks blocks,
        @NonNull TinybarValues tinybarValues,
        @NonNull SystemContractGasCalculator systemContractGasCalculator,
        @Nullable ContractOperationStreamBuilder streamBuilder,
        @Nullable PendingCreationMetadataRef pendingCreationRecordBuilderReference,
        @Nullable ContractGasUsage verifierGasUsage) {

    /** Creates a context without a caller-owned gas report, including for queries. */
    public HederaEvmContext(
            long gasPrice,
            boolean staticCall,
            boolean shouldChargeGasFees,
            @NonNull HederaEvmBlocks blocks,
            @NonNull TinybarValues tinybarValues,
            @NonNull SystemContractGasCalculator systemContractGasCalculator,
            @Nullable ContractOperationStreamBuilder streamBuilder,
            @Nullable PendingCreationMetadataRef pendingCreationRecordBuilderReference) {
        this(
                gasPrice,
                staticCall,
                shouldChargeGasFees,
                blocks,
                tinybarValues,
                systemContractGasCalculator,
                streamBuilder,
                pendingCreationRecordBuilderReference,
                null);
    }

    public HederaEvmContext {
        requireNonNull(blocks);
        requireNonNull(tinybarValues);
        requireNonNull(systemContractGasCalculator);
        if (streamBuilder != null) {
            requireNonNull(pendingCreationRecordBuilderReference);
        }
        if (pendingCreationRecordBuilderReference != null) {
            requireNonNull(streamBuilder);
        }
    }

    public BlockValues blockValuesOf(final long gasLimit) {
        return blocks.blockValuesOf(gasLimit);
    }

    public boolean isStaticCall() {
        return staticCall;
    }

    public boolean isTransaction() {
        return streamBuilder != null && pendingCreationRecordBuilderReference != null;
    }
}
