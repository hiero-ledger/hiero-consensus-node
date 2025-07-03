// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.inputs;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.CallContractOutput;
import com.hedera.hapi.block.stream.output.CreateContractOutput;
import com.hedera.hapi.block.stream.output.CreateScheduleOutput;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Groups the block items used to represent a single logical HAPI transaction, which itself may be part of a larger
 * transactional unit with parent/child relationships.
 * <p>
 * The transactionParts will be null for the batch inner transaction parts initially, because each batch inner
 * transaction will not be associated with an event transaction. It will be set using {@link #withPartsFromBatchParent(TransactionParts)}
 * when the inner transaction is processed.
 *
 * @param transactionParts the parts of the transaction.
 * @param transactionResult the result of processing the transaction
 * @param traces any traces associated with the transaction
 * @param outputs the output of processing the transaction
 * @param isTopLevel whether the transaction is a top-level transaction in its unit
 * @param hasEnrichedLegacyRecord whether the transaction has an enriched legacy record
 */
public record BlockTransactionParts(
        @Nullable TransactionParts transactionParts,
        @NonNull TransactionResult transactionResult,
        @Nullable List<TraceData> traces,
        @Nullable List<TransactionOutput> outputs,
        boolean isTopLevel,
        boolean hasEnrichedLegacyRecord) {
    /**
     * Returns whether this transaction is part of a batch.
     * @return true if it is part of a batch, false otherwise
     */
    public boolean inBatch() {
        return body().hasBatchKey();
    }

    /**
     * Returns the status of the transaction.
     *
     * @return the status
     */
    public ResponseCodeEnum status() {
        return transactionResult.status();
    }

    /**
     * Returns the body of the transaction.
     *
     * @return the body
     */
    public TransactionBody body() {
        return transactionParts.body();
    }

    /**
     * Returns the functionality of the transaction.
     *
     * @return the functionality
     */
    public HederaFunctionality functionality() {
        return transactionParts.function();
    }

    /**
     * Returns the transaction ID.
     *
     * @return the transaction ID
     */
    public TransactionID transactionIdOrThrow() {
        return transactionParts.body().transactionIDOrThrow();
    }

    /**
     * Returns the consensus timestamp.
     *
     * @return the consensus timestamp
     */
    public Timestamp consensusTimestamp() {
        return transactionResult.consensusTimestamp();
    }

    /**
     * Returns the transaction fee.
     *
     * @return the transaction fee
     */
    public long transactionFee() {
        return transactionResult.transactionFeeCharged();
    }

    /**
     * Returns the transfer list.
     *
     * @return the transfer list
     */
    public TransferList transferList() {
        return transactionResult.transferList();
    }

    /**
     * Returns the token transfer lists.
     *
     * @return the token transfer lists
     */
    public List<TokenTransferList> tokenTransferLists() {
        return transactionResult.tokenTransferLists();
    }

    /**
     * Returns the automatic token associations.
     *
     * @return the automatic token associations
     */
    public List<TokenAssociation> automaticTokenAssociations() {
        return transactionResult.automaticTokenAssociations();
    }

    /**
     * Returns the paid staking rewards.
     *
     * @return the paid staking rewards
     */
    public List<AccountAmount> paidStakingRewards() {
        return transactionResult.paidStakingRewards();
    }

    /**
     * Returns the memo.
     *
     * @return the memo
     */
    public String memo() {
        return transactionParts.body().memo();
    }

    /**
     * Returns the parent consensus timestamp.
     *
     * @return the parent consensus timestamp
     */
    public Timestamp parentConsensusTimestamp() {
        return transactionResult.parentConsensusTimestamp();
    }

    /**
     * Sets the transaction parts for this block transaction parts. This will be used for the batch inner transactions
     * that are not associated with an event transaction.
     *
     * @param transactionParts the transaction parts to set
     * @return a new instance of {@link BlockTransactionParts} with the updated transaction parts
     */
    public BlockTransactionParts withPartsFromBatchParent(@NonNull final TransactionParts transactionParts) {
        return new BlockTransactionParts(transactionParts, transactionResult, traces, outputs, false, true);
    }

    /**
     * Returns the hash of the transaction.
     *
     * @return the hash
     */
    public Bytes transactionHash() {
        final var transaction = transactionParts.wrapper();
        final Bytes transactionBytes;
        if (transaction.signedTransactionBytes().length() > 0) {
            transactionBytes = transaction.signedTransactionBytes();
        } else {
            transactionBytes = Transaction.PROTOBUF.toBytes(transaction);
        }
        return Bytes.wrap(noThrowSha384HashOf(transactionBytes.toByteArray()));
    }

    /**
     * Returns whether the transaction has an output.
     */
    public boolean hasContractOutput() {
        return outputs != null
                && outputs.stream().anyMatch(com.hedera.hapi.block.stream.output.TransactionOutput::hasContractCall);
    }

    public @NonNull List<TraceData> tracesOrThrow() {
        return requireNonNull(traces);
    }

    /**
     * Returns a contract call output or throws if it is not present.
     */
    public CallContractOutput callContractOutputOrThrow() {
        requireNonNull(outputs);
        return outputs.stream()
                .filter(TransactionOutput::hasContractCall)
                .findAny()
                .map(TransactionOutput::contractCallOrThrow)
                .orElseThrow();
    }

    /**
     * Returns a contract create output or throws if it is not present.
     */
    public CreateContractOutput createContractOutputOrThrow() {
        requireNonNull(outputs);
        return outputs.stream()
                .filter(TransactionOutput::hasContractCreate)
                .findAny()
                .map(TransactionOutput::contractCreateOrThrow)
                .orElseThrow();
    }

    public boolean hasTraces() {
        return traces != null && !traces.isEmpty();
    }

    /**
     * Returns a create schedule output or throws if it is not present.
     */
    public CreateScheduleOutput createScheduleOutputOrThrow() {
        requireNonNull(outputs);
        return outputs.stream()
                .filter(TransactionOutput::hasCreateSchedule)
                .findAny()
                .map(TransactionOutput::createScheduleOrThrow)
                .orElseThrow();
    }

    /**
     * Returns the {@link TransactionOutput} of the given kind if it is present.
     *
     * @param kind the kind of output
     * @return the output if present
     */
    public Optional<TransactionOutput> outputIfPresent(@NonNull final TransactionOutput.TransactionOneOfType kind) {
        if (outputs == null) {
            return Optional.empty();
        }
        return outputs.stream()
                .filter(output -> output.transaction().kind() == kind)
                .findAny();
    }

    /**
     * Returns the assessed custom fees.
     *
     * @return the assessed custom fees
     */
    public List<AssessedCustomFee> assessedCustomFees() {
        return transactionResult().assessedCustomFees();
    }
}
