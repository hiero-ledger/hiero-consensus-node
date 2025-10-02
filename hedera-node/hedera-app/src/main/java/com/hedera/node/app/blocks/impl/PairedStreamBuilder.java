// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.block.stream.trace.ExecutedInitcode;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.PendingAirdropRecord;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActions;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.node.app.service.addressbook.impl.records.NodeCreateStreamBuilder;
import com.hedera.node.app.service.consensus.impl.records.ConsensusCreateTopicStreamBuilder;
import com.hedera.node.app.service.consensus.impl.records.ConsensusSubmitMessageStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractDeleteStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractUpdateStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.EthereumTransactionStreamBuilder;
import com.hedera.node.app.service.file.impl.records.CreateFileStreamBuilder;
import com.hedera.node.app.service.schedule.ScheduleStreamBuilder;
import com.hedera.node.app.service.token.api.FeeStreamBuilder;
import com.hedera.node.app.service.token.records.ChildStreamBuilder;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.service.token.records.CryptoDeleteStreamBuilder;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.service.token.records.CryptoUpdateStreamBuilder;
import com.hedera.node.app.service.token.records.GenesisAccountStreamBuilder;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.service.token.records.NodeStakeUpdateStreamBuilder;
import com.hedera.node.app.service.token.records.TokenAccountWipeStreamBuilder;
import com.hedera.node.app.service.token.records.TokenAirdropStreamBuilder;
import com.hedera.node.app.service.token.records.TokenBaseStreamBuilder;
import com.hedera.node.app.service.token.records.TokenBurnStreamBuilder;
import com.hedera.node.app.service.token.records.TokenCreateStreamBuilder;
import com.hedera.node.app.service.token.records.TokenMintStreamBuilder;
import com.hedera.node.app.service.token.records.TokenUpdateStreamBuilder;
import com.hedera.node.app.service.util.impl.records.PrngStreamBuilder;
import com.hedera.node.app.service.util.impl.records.ReplayableFeeStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A temporary implementation of {@link StreamBuilder} that forwards all mutating calls to an
 * {@link BlockStreamBuilder} and a {@link RecordStreamBuilder}.
 */
public class PairedStreamBuilder
        implements StreamBuilder,
                ConsensusCreateTopicStreamBuilder,
                ConsensusSubmitMessageStreamBuilder,
                CreateFileStreamBuilder,
                CryptoCreateStreamBuilder,
                CryptoTransferStreamBuilder,
                ChildStreamBuilder,
                PrngStreamBuilder,
                ScheduleStreamBuilder,
                TokenMintStreamBuilder,
                TokenBurnStreamBuilder,
                TokenCreateStreamBuilder,
                ContractCreateStreamBuilder,
                ContractCallStreamBuilder,
                ContractUpdateStreamBuilder,
                EthereumTransactionStreamBuilder,
                CryptoDeleteStreamBuilder,
                TokenUpdateStreamBuilder,
                NodeStakeUpdateStreamBuilder,
                FeeStreamBuilder,
                ContractDeleteStreamBuilder,
                GenesisAccountStreamBuilder,
                ContractOperationStreamBuilder,
                TokenAccountWipeStreamBuilder,
                CryptoUpdateStreamBuilder,
                NodeCreateStreamBuilder,
                TokenAirdropStreamBuilder,
                ReplayableFeeStreamBuilder,
                HookDispatchStreamBuilder {
    private final BlockStreamBuilder blockStreamBuilder;
    private final RecordStreamBuilder recordStreamBuilder;

    public PairedStreamBuilder(
            @NonNull final ReversingBehavior reversingBehavior,
            @NonNull final SignedTxCustomizer customizer,
            @NonNull final HandleContext.TransactionCategory category) {
        recordStreamBuilder = new RecordStreamBuilder(reversingBehavior, customizer, category);
        blockStreamBuilder = new BlockStreamBuilder(reversingBehavior, customizer, category);
    }

    @Override
    public List<StateChange> getStateChanges() {
        return blockStreamBuilder.getStateChanges();
    }

    @Override
    public StreamBuilder stateChanges(@NonNull List<StateChange> stateChanges) {
        blockStreamBuilder.stateChanges(stateChanges);
        return this;
    }

    @Override
    public ContractOperationStreamBuilder testForIdenticalKeys(@NonNull final Predicate<Object> test) {
        blockStreamBuilder.testForIdenticalKeys(test);
        return this;
    }

    @Override
    public @Nullable Predicate<Object> logicallyIdenticalValueTest() {
        return blockStreamBuilder.logicallyIdenticalValueTest();
    }

    public BlockStreamBuilder blockStreamBuilder() {
        return blockStreamBuilder;
    }

    public RecordStreamBuilder recordStreamBuilder() {
        return recordStreamBuilder;
    }

    @Override
    public @NonNull PairedStreamBuilder signedTx(@NonNull final SignedTransaction signedTx) {
        recordStreamBuilder.signedTx(signedTx);
        blockStreamBuilder.signedTx(signedTx);
        return this;
    }

    @Override
    public StreamBuilder functionality(@NonNull final HederaFunctionality functionality) {
        recordStreamBuilder.functionality(functionality);
        blockStreamBuilder.functionality(functionality);
        return this;
    }

    @Override
    public PairedStreamBuilder serializedSignedTx(@Nullable final Bytes serializedSignedTx) {
        recordStreamBuilder.serializedSignedTx(serializedSignedTx);
        blockStreamBuilder.serializedSignedTx(serializedSignedTx);
        return this;
    }

    @Override
    public int getNumAutoAssociations() {
        return blockStreamBuilder.getNumAutoAssociations();
    }

    @Override
    public ScheduleID scheduleID() {
        return blockStreamBuilder.scheduleID();
    }

    @Override
    public TokenAirdropStreamBuilder addPendingAirdrop(@NonNull final PendingAirdropRecord pendingAirdropRecord) {
        recordStreamBuilder.addPendingAirdrop(pendingAirdropRecord);
        blockStreamBuilder.addPendingAirdrop(pendingAirdropRecord);
        return this;
    }

    @Override
    public Set<AccountID> explicitRewardSituationIds() {
        return recordStreamBuilder.explicitRewardSituationIds();
    }

    @Override
    public List<AccountAmount> getPaidStakingRewards() {
        return recordStreamBuilder.getPaidStakingRewards();
    }

    @Override
    public boolean hasContractResult() {
        return recordStreamBuilder.hasContractResult();
    }

    @Override
    public long getGasUsedForContractTxn() {
        return recordStreamBuilder.getGasUsedForContractTxn();
    }

    @NonNull
    @Override
    public ResponseCodeEnum status() {
        return recordStreamBuilder.status();
    }

    @NonNull
    @Override
    public TransactionBody transactionBody() {
        return recordStreamBuilder.transactionBody();
    }

    @Override
    public long transactionFee() {
        return recordStreamBuilder.transactionFee();
    }

    @Override
    public HandleContext.TransactionCategory category() {
        return recordStreamBuilder.category();
    }

    @Override
    public ReversingBehavior reversingBehavior() {
        return recordStreamBuilder.reversingBehavior();
    }

    @Override
    public void nullOutSideEffectFields() {
        recordStreamBuilder.nullOutSideEffectFields();
        blockStreamBuilder.nullOutSideEffectFields();
    }

    @Override
    public StreamBuilder syncBodyIdFromRecordId() {
        recordStreamBuilder.syncBodyIdFromRecordId();
        blockStreamBuilder.syncBodyIdFromRecordId();
        return this;
    }

    @Override
    public StreamBuilder consensusTimestamp(@NonNull final Instant now) {
        recordStreamBuilder.consensusTimestamp(now);
        blockStreamBuilder.consensusTimestamp(now);
        return this;
    }

    @Override
    public TransactionID transactionID() {
        return recordStreamBuilder.transactionID();
    }

    @Override
    public StreamBuilder transactionID(@NonNull final TransactionID transactionID) {
        recordStreamBuilder.transactionID(transactionID);
        blockStreamBuilder.transactionID(transactionID);
        return this;
    }

    @Override
    public StreamBuilder parentConsensus(@NonNull final Instant parentConsensus) {
        recordStreamBuilder.parentConsensus(parentConsensus);
        blockStreamBuilder.parentConsensus(parentConsensus);
        return this;
    }

    @Override
    public StreamBuilder exchangeRate(@Nullable ExchangeRateSet exchangeRate) {
        recordStreamBuilder.exchangeRate(exchangeRate);
        blockStreamBuilder.exchangeRate(exchangeRate);
        return this;
    }

    @Override
    public StreamBuilder congestionMultiplier(final long congestionMultiplier) {
        return null;
    }

    @NonNull
    @Override
    public NodeCreateStreamBuilder nodeID(long nodeID) {
        recordStreamBuilder.nodeID(nodeID);
        blockStreamBuilder.nodeID(nodeID);
        return this;
    }

    @NonNull
    @Override
    public ConsensusCreateTopicStreamBuilder topicID(@NonNull TopicID topicID) {
        recordStreamBuilder.topicID(topicID);
        blockStreamBuilder.topicID(topicID);
        return this;
    }

    @NonNull
    @Override
    public ConsensusSubmitMessageStreamBuilder topicSequenceNumber(long topicSequenceNumber) {
        recordStreamBuilder.topicSequenceNumber(topicSequenceNumber);
        blockStreamBuilder.topicSequenceNumber(topicSequenceNumber);
        return this;
    }

    @NonNull
    @Override
    public ConsensusSubmitMessageStreamBuilder topicRunningHash(@NonNull Bytes topicRunningHash) {
        recordStreamBuilder.topicRunningHash(topicRunningHash);
        blockStreamBuilder.topicRunningHash(topicRunningHash);
        return this;
    }

    @NonNull
    @Override
    public ConsensusSubmitMessageStreamBuilder topicRunningHashVersion(long topicRunningHashVersion) {
        recordStreamBuilder.topicRunningHashVersion(topicRunningHashVersion);
        blockStreamBuilder.topicRunningHashVersion(topicRunningHashVersion);
        return this;
    }

    @NonNull
    @Override
    public List<AssessedCustomFee> getAssessedCustomFees() {
        return recordStreamBuilder.getAssessedCustomFees();
    }

    @Override
    public List<Long> serialNumbers() {
        return recordStreamBuilder.serialNumbers();
    }

    @NonNull
    @Override
    public PairedStreamBuilder status(@NonNull ResponseCodeEnum status) {
        recordStreamBuilder.status(status);
        blockStreamBuilder.status(status);
        return this;
    }

    @NonNull
    @Override
    public PairedStreamBuilder contractID(@Nullable ContractID contractId) {
        recordStreamBuilder.contractID(contractId);
        blockStreamBuilder.contractID(contractId);
        return this;
    }

    @NonNull
    @Override
    public EthereumTransactionStreamBuilder newSenderNonce(final long senderNonce) {
        blockStreamBuilder.newSenderNonce(senderNonce);
        return this;
    }

    /**
     * Sets the receipt contractID;
     * This is used for HAPI and Ethereum contract creation transactions.
     *
     * @param contractId the {@link ContractID} for the receipt
     * @return the builder
     */
    @NonNull
    @Override
    public PairedStreamBuilder createdContractID(@Nullable final ContractID contractId) {
        recordStreamBuilder.createdContractID(contractId);
        blockStreamBuilder.createdContractID(contractId);
        return this;
    }

    @NonNull
    @Override
    public PairedStreamBuilder createdEvmAddress(@Nullable Bytes evmAddress) {
        blockStreamBuilder.createdEvmAddress(evmAddress);
        return this;
    }

    @NonNull
    @Override
    public PairedStreamBuilder changedNonceInfo(@NonNull final List<ContractNonceInfo> nonceInfos) {
        blockStreamBuilder.changedNonceInfo(nonceInfos);
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder createdContractIds(@NonNull final List<ContractID> contractIds) {
        blockStreamBuilder.createdContractIds(contractIds);
        return this;
    }

    @NonNull
    @Override
    public PairedStreamBuilder contractCreateResult(@Nullable ContractFunctionResult result) {
        recordStreamBuilder.contractCreateResult(result);
        return this;
    }

    @NonNull
    @Override
    public PairedStreamBuilder addLogs(@NonNull final List<EvmTransactionLog> logs) {
        blockStreamBuilder.addLogs(logs);
        return this;
    }

    @NonNull
    @Override
    public EthereumTransactionStreamBuilder ethereumHash(@NonNull Bytes ethereumHash) {
        recordStreamBuilder.ethereumHash(ethereumHash);
        blockStreamBuilder.ethereumHash(ethereumHash);
        return this;
    }

    @Override
    public void trackExplicitRewardSituation(@NonNull AccountID accountId) {
        recordStreamBuilder.trackExplicitRewardSituation(accountId);
        blockStreamBuilder.trackExplicitRewardSituation(accountId);
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder addContractActions(
            @NonNull ContractActions contractActions, boolean isMigration) {
        recordStreamBuilder.addContractActions(contractActions, isMigration);
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder addContractBytecode(
            @NonNull final ContractBytecode contractBytecode, final boolean isMigration) {
        recordStreamBuilder.addContractBytecode(contractBytecode, isMigration);
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder addActions(@NonNull final List<ContractAction> actions) {
        blockStreamBuilder.addActions(actions);
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder addInitcode(@NonNull final ExecutedInitcode initcode) {
        blockStreamBuilder.addInitcode(initcode);
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder addContractStateChanges(
            @NonNull ContractStateChanges contractStateChanges, boolean isMigration) {
        recordStreamBuilder.addContractStateChanges(contractStateChanges, isMigration);
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder addContractSlotUsages(@NonNull final List<ContractSlotUsage> slotUsages) {
        blockStreamBuilder.addContractSlotUsages(slotUsages);
        return this;
    }

    @NonNull
    @Override
    public CreateFileStreamBuilder fileID(@NonNull FileID fileID) {
        recordStreamBuilder.fileID(fileID);
        blockStreamBuilder.fileID(fileID);
        return this;
    }

    @NonNull
    @Override
    public ScheduleStreamBuilder scheduleRef(ScheduleID scheduleRef) {
        recordStreamBuilder.scheduleRef(scheduleRef);
        blockStreamBuilder.scheduleRef(scheduleRef);
        return this;
    }

    @NonNull
    @Override
    public ScheduleStreamBuilder scheduleID(ScheduleID scheduleID) {
        recordStreamBuilder.scheduleID(scheduleID);
        blockStreamBuilder.scheduleID(scheduleID);
        return this;
    }

    @NonNull
    @Override
    public ScheduleStreamBuilder scheduledTransactionID(TransactionID scheduledTransactionID) {
        recordStreamBuilder.scheduledTransactionID(scheduledTransactionID);
        blockStreamBuilder.scheduledTransactionID(scheduledTransactionID);
        return this;
    }

    @Override
    public TransferList transferList() {
        return recordStreamBuilder.transferList();
    }

    @Override
    public List<TokenTransferList> tokenTransferLists() {
        return recordStreamBuilder.tokenTransferLists();
    }

    @NonNull
    @Override
    public PairedStreamBuilder accountID(@NonNull AccountID accountID) {
        recordStreamBuilder.accountID(accountID);
        blockStreamBuilder.accountID(accountID);
        return this;
    }

    @NonNull
    @Override
    public CryptoCreateStreamBuilder evmAddress(@NonNull Bytes evmAddress) {
        recordStreamBuilder.evmAddress(evmAddress);
        blockStreamBuilder.evmAddress(evmAddress);
        return this;
    }

    @NonNull
    @Override
    public PairedStreamBuilder transactionFee(@NonNull long transactionFee) {
        recordStreamBuilder.transactionFee(transactionFee);
        blockStreamBuilder.transactionFee(transactionFee);
        return this;
    }

    @NonNull
    @Override
    public PairedStreamBuilder memo(@NonNull String memo) {
        recordStreamBuilder.memo(memo);
        blockStreamBuilder.memo(memo);
        return this;
    }

    @NonNull
    @Override
    public CryptoTransferStreamBuilder transferList(@NonNull final TransferList hbarTransfers) {
        recordStreamBuilder.transferList(hbarTransfers);
        blockStreamBuilder.transferList(hbarTransfers);
        return this;
    }

    @Override
    public void setReplayedFees(@NonNull final TransferList transferList) {
        recordStreamBuilder.setReplayedFees(transferList);
        blockStreamBuilder.setReplayedFees(transferList);
    }

    @NonNull
    @Override
    public CryptoTransferStreamBuilder tokenTransferLists(@NonNull List<TokenTransferList> tokenTransferLists) {
        recordStreamBuilder.tokenTransferLists(tokenTransferLists);
        blockStreamBuilder.tokenTransferLists(tokenTransferLists);
        return this;
    }

    @NonNull
    @Override
    public CryptoTransferStreamBuilder assessedCustomFees(@NonNull List<AssessedCustomFee> assessedCustomFees) {
        recordStreamBuilder.assessedCustomFees(assessedCustomFees);
        blockStreamBuilder.assessedCustomFees(assessedCustomFees);
        return this;
    }

    @Override
    public CryptoTransferStreamBuilder paidStakingRewards(@NonNull List<AccountAmount> paidStakingRewards) {
        recordStreamBuilder.paidStakingRewards(paidStakingRewards);
        blockStreamBuilder.paidStakingRewards(paidStakingRewards);
        return this;
    }

    @Override
    public PairedStreamBuilder addAutomaticTokenAssociation(@NonNull TokenAssociation tokenAssociation) {
        recordStreamBuilder.addAutomaticTokenAssociation(tokenAssociation);
        blockStreamBuilder.addAutomaticTokenAssociation(tokenAssociation);
        return this;
    }

    @NonNull
    @Override
    public PairedStreamBuilder contractCallResult(@Nullable ContractFunctionResult result) {
        recordStreamBuilder.contractCallResult(result);
        return this;
    }

    @NonNull
    @Override
    public ContractCallStreamBuilder evmCallTransactionResult(@Nullable final EvmTransactionResult result) {
        blockStreamBuilder.evmCallTransactionResult(result);
        return this;
    }

    @NonNull
    @Override
    public ContractCreateStreamBuilder evmCreateTransactionResult(@Nullable final EvmTransactionResult result) {
        blockStreamBuilder.evmCreateTransactionResult(result);
        return this;
    }

    @NonNull
    @Override
    public TokenCreateStreamBuilder tokenID(@NonNull TokenID tokenID) {
        recordStreamBuilder.tokenID(tokenID);
        blockStreamBuilder.tokenID(tokenID);
        return this;
    }

    @Override
    public TokenID tokenID() {
        return recordStreamBuilder.tokenID();
    }

    @NonNull
    @Override
    public PairedStreamBuilder serialNumbers(@NonNull List<Long> serialNumbers) {
        recordStreamBuilder.serialNumbers(serialNumbers);
        blockStreamBuilder.serialNumbers(serialNumbers);
        return this;
    }

    @Override
    public PairedStreamBuilder newTotalSupply(long newTotalSupply) {
        recordStreamBuilder.newTotalSupply(newTotalSupply);
        blockStreamBuilder.newTotalSupply(newTotalSupply);
        return this;
    }

    @Override
    public long getNewTotalSupply() {
        return recordStreamBuilder.getNewTotalSupply();
    }

    @Override
    public TokenBaseStreamBuilder tokenType(@NonNull TokenType tokenType) {
        recordStreamBuilder.tokenType(tokenType);
        blockStreamBuilder.tokenType(tokenType);
        return this;
    }

    @NonNull
    @Override
    public PrngStreamBuilder entropyNumber(int num) {
        recordStreamBuilder.entropyNumber(num);
        blockStreamBuilder.entropyNumber(num);
        return this;
    }

    @NonNull
    @Override
    public PairedStreamBuilder entropyBytes(@NonNull Bytes prngBytes) {
        recordStreamBuilder.entropyBytes(prngBytes);
        blockStreamBuilder.entropyBytes(prngBytes);
        return this;
    }

    @Override
    public int getNumberOfDeletedAccounts() {
        return recordStreamBuilder.getNumberOfDeletedAccounts();
    }

    @Nullable
    @Override
    public AccountID getDeletedAccountBeneficiaryFor(@NonNull AccountID deletedAccountID) {
        return recordStreamBuilder.getDeletedAccountBeneficiaryFor(deletedAccountID);
    }

    @Override
    public void addBeneficiaryForDeletedAccount(
            @NonNull AccountID deletedAccountID, @NonNull AccountID beneficiaryForDeletedAccount) {
        recordStreamBuilder.addBeneficiaryForDeletedAccount(deletedAccountID, beneficiaryForDeletedAccount);
        blockStreamBuilder.addBeneficiaryForDeletedAccount(deletedAccountID, beneficiaryForDeletedAccount);
    }

    @Override
    public HederaFunctionality functionality() {
        return blockStreamBuilder.functionality();
    }

    @Override
    public void nextHookId(final long nextHookId) {
        recordStreamBuilder.nextHookId(nextHookId);
        blockStreamBuilder.nextHookId(nextHookId);
    }

    @Override
    public long getNextHookId() {
        return blockStreamBuilder.getNextHookId();
    }
}
