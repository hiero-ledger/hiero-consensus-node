// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.inputs;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.CallContractOutput;
import com.hedera.hapi.block.stream.output.CreateContractOutput;
import com.hedera.hapi.block.stream.output.CreateScheduleOutput;
import com.hedera.hapi.block.stream.output.CryptoTransferOutput;
import com.hedera.hapi.block.stream.output.TokenAirdropOutput;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
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
import java.util.stream.Stream;

/**
 * Groups the block items used to represent a single logical HAPI transaction, which itself may be part of a larger
 * transactional unit with parent/child relationships.
 * @param transactionParts the parts of the transaction
 * @param transactionResult the result of processing the transaction
 * @param transactionOutputs the output of processing the transaction
 */
public record BlockTransactionParts(
        @NonNull TransactionParts transactionParts,
        @NonNull TransactionResult transactionResult,
        @Nullable TransactionOutput... transactionOutputs) {

    /**
     * Returns the status of the transaction.
     * @return the status
     */
    public ResponseCodeEnum status() {
        return transactionResult.status();
    }

    /**
     * Returns the body of the transaction.
     * @return the body
     */
    public TransactionBody body() {
        return transactionParts.body();
    }

    /**
     * Returns the functionality of the transaction.
     * @return the functionality
     */
    public HederaFunctionality functionality() {
        return transactionParts.function();
    }

    /**
     * Returns the transaction ID.
     * @return the transaction ID
     */
    public TransactionID transactionIdOrThrow() {
        return transactionParts.body().transactionIDOrThrow();
    }

    /**
     * Returns the consensus timestamp.
     * @return the consensus timestamp
     */
    public Timestamp consensusTimestamp() {
        return transactionResult.consensusTimestamp();
    }

    /**
     * Returns the transaction fee.
     * @return the transaction fee
     */
    public long transactionFee() {
        return transactionResult.transactionFeeCharged();
    }

    /**
     * Returns the transfer list.
     * @return the transfer list
     */
    public TransferList transferList() {
        return transactionResult.transferList();
    }

    /**
     * Returns the token transfer lists.
     * @return the token transfer lists
     */
    public List<TokenTransferList> tokenTransferLists() {
        return transactionResult.tokenTransferLists();
    }

    /**
     * Returns the automatic token associations.
     * @return the automatic token associations
     */
    public List<TokenAssociation> automaticTokenAssociations() {
        return transactionResult.automaticTokenAssociations();
    }

    /**
     * Returns the paid staking rewards.
     * @return the paid staking rewards
     */
    public List<AccountAmount> paidStakingRewards() {
        return transactionResult.paidStakingRewards();
    }

    /**
     * Returns the memo.
     * @return the memo
     */
    public String memo() {
        return transactionParts.body().memo();
    }

    /**
     * Returns the hash of the transaction.
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
     * Constructs a new {@link BlockTransactionParts} that includes an output.
     * @param transactionParts the parts of the transaction
     * @param transactionResult the result of processing the transaction
     * @param transactionOutputs the outputs of processing the transaction
     * @return the constructed object
     */
    public static BlockTransactionParts withOutputs(
            @NonNull final TransactionParts transactionParts,
            @NonNull final TransactionResult transactionResult,
            @NonNull final TransactionOutput... transactionOutputs) {
        requireNonNull(transactionParts);
        requireNonNull(transactionResult);
        requireNonNull(transactionOutputs);
        return new BlockTransactionParts(transactionParts, transactionResult, transactionOutputs);
    }

    /**
     * Constructs a new {@link BlockTransactionParts} that does not include an output.
     * @param transactionParts the parts of the transaction
     * @param transactionResult the result of processing the transaction
     * @return the constructed object
     */
    public static BlockTransactionParts sansOutput(
            @NonNull final TransactionParts transactionParts, @NonNull final TransactionResult transactionResult) {
        requireNonNull(transactionParts);
        requireNonNull(transactionResult);
        return new BlockTransactionParts(transactionParts, transactionResult);
    }

    /**
     * Returns whether the transaction has an output.
     */
    public boolean hasOutputs() {
        return transactionOutputs != null && transactionOutputs.length > 0;
    }

    /**
     * Returns whether the transaction has an output.
     */
    public boolean hasContractOutput() {
        return transactionOutputs != null && Stream.of(transactionOutputs).anyMatch(TransactionOutput::hasContractCall);
    }

    /**
     * Returns a contract call output or throws if it is not present.
     */
    public CallContractOutput callContractOutputOrThrow() {
        requireNonNull(transactionOutputs);
        return Stream.of(transactionOutputs)
                .filter(TransactionOutput::hasContractCall)
                .findAny()
                .map(TransactionOutput::contractCallOrThrow)
                .orElseThrow();
    }

    /**
     * Returns a contract create output or throws if it is not present.
     */
    public CreateContractOutput createContractOutputOrThrow() {
        requireNonNull(transactionOutputs);
        return Stream.of(transactionOutputs)
                .filter(TransactionOutput::hasContractCreate)
                .findAny()
                .map(TransactionOutput::contractCreateOrThrow)
                .orElseThrow();
    }

    /**
     * Returns a create schedule output or throws if it is not present.
     */
    public CreateScheduleOutput createScheduleOutputOrThrow() {
        requireNonNull(transactionOutputs);
        return Stream.of(transactionOutputs)
                .filter(TransactionOutput::hasCreateSchedule)
                .findAny()
                .map(TransactionOutput::createScheduleOrThrow)
                .orElseThrow();
    }

    /**
     * Returns a token airdrop output or throws if it is not present.
     */
    public TokenAirdropOutput tokenAirdropOutputOrThrow() {
        requireNonNull(transactionOutputs);
        return Stream.of(transactionOutputs)
                .filter(TransactionOutput::hasTokenAirdrop)
                .findAny()
                .map(TransactionOutput::tokenAirdropOrThrow)
                .orElseThrow();
    }

    /**
     * Returns the {@link TransactionOutput} of the given kind if it is present.
     * @param kind the kind of output
     * @return the output if present
     */
    public Optional<TransactionOutput> outputIfPresent(@NonNull final TransactionOutput.TransactionOneOfType kind) {
        if (transactionOutputs == null) {
            return Optional.empty();
        }
        return Stream.of(transactionOutputs)
                .filter(output -> output.transaction().kind() == kind)
                .findAny();
    }

    /**
     * Returns the assessed custom fees.
     * @return the assessed custom fees
     */
    public List<AssessedCustomFee> assessedCustomFees() {
        return outputIfPresent(TransactionOutput.TransactionOneOfType.CRYPTO_TRANSFER)
                .map(TransactionOutput::cryptoTransferOrThrow)
                .map(CryptoTransferOutput::assessedCustomFees)
                .orElse(emptyList());
    }
}
