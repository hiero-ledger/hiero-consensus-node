// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadataRef;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.evm.frame.BlockValues;

public record HederaEvmContext(
        long gasPrice, // weibar
        boolean staticCall,
        @NonNull HederaEvmBlocks blocks,
        @NonNull TinybarValues tinybarValues,
        @NonNull SystemContractGasCalculator systemContractGasCalculator,
        @Nullable ContractOperationStreamBuilder recordBuilder,
        @Nullable PendingCreationMetadataRef pendingCreationRecordBuilderReference) {

    public HederaEvmContext {
        requireNonNull(blocks);
        requireNonNull(tinybarValues);
        requireNonNull(systemContractGasCalculator);
        if (recordBuilder != null) {
            requireNonNull(pendingCreationRecordBuilderReference);
        }
        if (pendingCreationRecordBuilderReference != null) {
            requireNonNull(recordBuilder);
        }
    }

    public BlockValues blockValuesOf(final long gasLimit) {
        return blocks.blockValuesOf(gasLimit);
    }

    public boolean isNoopGasContext() {
        return staticCall || gasPrice == 0;
    }

    public boolean isTransaction() {
        return recordBuilder != null && pendingCreationRecordBuilderReference != null;
    }
}
