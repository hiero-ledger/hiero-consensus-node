// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.hapi.node.base.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.ContractLoginfo;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.streams.ContractStateChange;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.hapi.streams.StorageChange;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RecordStreamBuilderTest {
    private static final SignedTransaction SIGNED_TX =
            SignedTransaction.newBuilder().bodyBytes(Bytes.wrap("body")).build();

    @Test
    void successfulTransactionPreservesStorageWritesInSidecar() {
        final var stateChanges = new ContractStateChanges(List.of(new ContractStateChange(
                ContractID.DEFAULT,
                List.of(new StorageChange(
                        Bytes.wrap(new byte[] {0x01}), Bytes.EMPTY, Bytes.wrap(new byte[] {0x02}))))));
        final var record = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER)
                .signedTx(SIGNED_TX)
                .transactionID(TransactionID.DEFAULT)
                .status(SUCCESS)
                .addContractStateChanges(stateChanges, false)
                .build();

        assertThat(record.transactionSidecarRecords())
                .containsExactly(new TransactionSidecarRecord(
                        Timestamp.DEFAULT,
                        false,
                        new OneOf<>(TransactionSidecarRecord.SidecarRecordsOneOfType.STATE_CHANGES, stateChanges)));
    }

    @Test
    void revertedSuccessOmitsStorageChangeSidecar() {
        final var stateChanges = new ContractStateChanges(List.of(new ContractStateChange(
                ContractID.DEFAULT,
                List.of(new StorageChange(
                        Bytes.wrap(new byte[] {0x01}), Bytes.EMPTY, Bytes.wrap(new byte[] {0x02}))))));
        final var record = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER)
                .signedTx(SIGNED_TX)
                .transactionID(TransactionID.DEFAULT)
                .status(REVERTED_SUCCESS)
                .addContractStateChanges(stateChanges, false)
                .build();

        assertThat(record.transactionSidecarRecords()).noneMatch(sidecar -> sidecar.hasStateChanges());
    }

    @Test
    void nullOutSideEffectFieldsClearsContractCallResultFields() {
        final var result = ContractFunctionResult.newBuilder()
                .bloom(Bytes.wrap(new byte[] {1, 2, 3}))
                .logInfo(List.of(ContractLoginfo.DEFAULT))
                .createdContractIDs(List.of(ContractID.DEFAULT))
                .build();
        final var builder = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER)
                .signedTx(SIGNED_TX)
                .transactionID(TransactionID.DEFAULT)
                .contractCallResult(result);

        builder.nullOutSideEffectFields();

        final var record = builder.build().transactionRecord();

        assertThat(record.contractCallResult().logInfo()).isEmpty();
        assertThat(record.contractCallResult().bloom()).isEqualTo(Bytes.EMPTY);
        assertThat(record.contractCallResult().createdContractIDs()).isEmpty();
    }

    @Test
    void nullOutSideEffectFieldsClearsContractCreateResultFields() {
        final var result = ContractFunctionResult.newBuilder()
                .bloom(Bytes.wrap(new byte[] {1, 2, 3}))
                .logInfo(List.of(ContractLoginfo.DEFAULT))
                .createdContractIDs(List.of(ContractID.DEFAULT))
                .build();
        final var builder = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER)
                .signedTx(SIGNED_TX)
                .transactionID(TransactionID.DEFAULT)
                .createdContractID(ContractID.DEFAULT) // sets isContractCreate = true
                .contractCreateResult(result);

        builder.nullOutSideEffectFields();

        final var record = builder.build().transactionRecord();

        assertThat(record.contractCreateResult().logInfo()).isEmpty();
        assertThat(record.contractCreateResult().bloom()).isEqualTo(Bytes.EMPTY);
        assertThat(record.contractCreateResult().createdContractIDs()).isEmpty();
    }
}
