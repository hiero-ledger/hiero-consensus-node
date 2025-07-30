// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.hapi.node.base.ResponseCodeEnum.IDENTICAL_SCHEDULE_ALREADY_CREATED;
import static com.hedera.node.app.service.token.impl.comparator.TokenComparators.PENDING_AIRDROP_ID_COMPARATOR;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logEndTransactionRecord;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;

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
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.Transaction;
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
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActions;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.hapi.util.HapiUtils;
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
import com.hedera.node.app.service.token.records.NodeStakeUpdateStreamBuilder;
import com.hedera.node.app.service.token.records.TokenAccountWipeStreamBuilder;
import com.hedera.node.app.service.token.records.TokenAirdropStreamBuilder;
import com.hedera.node.app.service.token.records.TokenBurnStreamBuilder;
import com.hedera.node.app.service.token.records.TokenCreateStreamBuilder;
import com.hedera.node.app.service.token.records.TokenMintStreamBuilder;
import com.hedera.node.app.service.token.records.TokenUpdateStreamBuilder;
import com.hedera.node.app.service.util.impl.records.PrngStreamBuilder;
import com.hedera.node.app.service.util.impl.records.ReplayableFeeStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.app.state.SingleTransactionRecord.TransactionOutputs;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.hiero.base.crypto.DigestType;

/**
 * A custom builder for create a {@link SingleTransactionRecord}.
 *
 * <p>The protobuf definition for the record files is defined such that a single protobuf object intermixes the
 * possible fields for all different types of transaction in a single object definition. We wanted to provide something
 * nicer and more modular for service authors, so we broke out each logical grouping of state in the record file into
 * different interfaces, such as {@link ConsensusSubmitMessageStreamBuilder} and {@link CreateFileStreamBuilder}, and
 * so forth. Services interact with these builder interfaces, and are thus isolated from details that don't pertain to
 * their service type.
 *
 * <p>This class is an ugly superset of all fields for all transaction types. It is masked down to a sensible subset by
 * the interfaces for specific transaction types.
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class RecordStreamBuilder
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
                ReplayableFeeStreamBuilder {
    private static final Comparator<TokenAssociation> TOKEN_ASSOCIATION_COMPARATOR =
            Comparator.<TokenAssociation>comparingLong(a -> a.tokenId().tokenNum())
                    .thenComparingLong(a -> a.accountIdOrThrow().accountNum());
    private static final Comparator<PendingAirdropRecord> PENDING_AIRDROP_RECORD_COMPARATOR =
            Comparator.comparing(PendingAirdropRecord::pendingAirdropIdOrThrow, PENDING_AIRDROP_ID_COMPARATOR);
    // base transaction data
    private SignedTransaction signedTx;

    @Nullable
    private Bytes serializedSignedTx = null;
    // fields needed for TransactionRecord
    // Mutable because the provisional consensus timestamp assigned on dispatch could
    // change when removable records appear "between" this record and the parent record
    private Instant consensusNow;
    private Instant parentConsensus;
    private TransactionID transactionID;
    private List<TokenTransferList> tokenTransferLists = new LinkedList<>();
    private List<AssessedCustomFee> assessedCustomFees = new LinkedList<>();

    private List<PendingAirdropRecord> pendingAirdropRecords = new LinkedList<>();
    private List<TokenAssociation> automaticTokenAssociations = new LinkedList<>();

    private List<AccountAmount> paidStakingRewards = new LinkedList<>();
    private final TransactionRecord.Builder transactionRecordBuilder = TransactionRecord.newBuilder();
    private TransferList transferList = TransferList.DEFAULT;

    // fields needed for TransactionReceipt
    private ResponseCodeEnum status = ResponseCodeEnum.OK;
    private ExchangeRateSet exchangeRate = ExchangeRateSet.DEFAULT;
    private List<Long> serialNumbers = new LinkedList<>();
    private long newTotalSupply = 0L;
    private final TransactionReceipt.Builder transactionReceiptBuilder = TransactionReceipt.newBuilder();
    // Sidecar data, booleans are the migration flag
    @Nullable
    private List<AbstractMap.SimpleEntry<ContractStateChanges, Boolean>> contractStateChanges;

    private List<AbstractMap.SimpleEntry<ContractActions, Boolean>> contractActions = new LinkedList<>();
    private List<AbstractMap.SimpleEntry<ContractBytecode, Boolean>> contractBytecodes = new LinkedList<>();

    // Fields that are not in TransactionRecord, but are needed for computing staking rewards
    // These are not persisted to the record file
    private final Map<AccountID, AccountID> deletedAccountBeneficiaries = new HashMap<>();

    // A set of ids that should be explicitly considered as in a "reward situation",
    // despite the canonical definition of a reward situation; needed for mono-service
    // fidelity only
    @Nullable
    private Set<AccountID> explicitRewardReceiverIds;

    // While the fee is sent to the underlying builder all the time, it is also cached here because, as of today,
    // there is no way to get the transaction fee from the PBJ object.
    private long transactionFee;
    private ContractFunctionResult contractFunctionResult;

    // Used for some child records builders.
    private final ReversingBehavior reversingBehavior;

    // Category of the record
    private final TransactionCategory category;

    // Used to customize the externalized form of a dispatched child transaction, right before
    // its record stream item is built; lets the contract service externalize certain dispatched
    // CryptoCreate transactions as ContractCreate synthetic transactions
    private final SignedTxCustomizer customizer;

    private TokenID tokenID;
    private ScheduleID scheduleID;
    private TokenType tokenType;
    private HederaFunctionality function;

    private boolean isContractCreate;

    /**
     * ops duration used by the contract transaction
     */
    private long opsDuration;

    public RecordStreamBuilder(
            @NonNull final ReversingBehavior reversingBehavior,
            @NonNull final SignedTxCustomizer customizer,
            @NonNull final TransactionCategory category) {
        this.consensusNow = Instant.EPOCH;
        this.reversingBehavior = requireNonNull(reversingBehavior, "reversingBehavior must not be null");
        this.customizer = requireNonNull(customizer, "customizer must not be null");
        this.category = requireNonNull(category, "category must not be null");
    }

    /**
     * Builds single transaction record.
     *
     * @return the transaction record
     */
    public SingleTransactionRecord build() {
        if (customizer != NOOP_SIGNED_TX_CUSTOMIZER) {
            signedTx = customizer.apply(signedTx);
            serializedSignedTx = SignedTransaction.PROTOBUF.toBytes(signedTx);
        }
        final var builder = transactionReceiptBuilder.serialNumbers(serialNumbers);
        // FUTURE : In mono-service exchange rate is not set in preceding child records.
        // This should be changed after differential testing
        if (exchangeRate != null && exchangeRate.hasCurrentRate() && exchangeRate.hasNextRate()) {
            builder.exchangeRate(exchangeRate);
        }
        final var transactionReceipt = builder.build();

        // The "re-wrapped" transaction for legacy record stream compliance
        final Transaction transaction;
        final Bytes transactionHash;
        try {
            final MessageDigest digest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
            if (signedTx.useSerializedTxMessageHashAlgorithm()) {
                transaction = Transaction.newBuilder()
                        .bodyBytes(signedTx.bodyBytes())
                        .sigMap(signedTx.sigMap())
                        .build();
                final var legacyBytes = Transaction.PROTOBUF.toBytes(transaction);
                transactionHash = Bytes.wrap(digest.digest(legacyBytes.toByteArray()));
            } else {
                if (serializedSignedTx == null) {
                    serializedSignedTx = SignedTransaction.PROTOBUF.toBytes(signedTx);
                }
                transaction = Transaction.newBuilder()
                        .signedTransactionBytes(serializedSignedTx)
                        .build();
                transactionHash = Bytes.wrap(digest.digest(serializedSignedTx.toByteArray()));
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        final Timestamp consensusTimestamp = HapiUtils.asTimestamp(consensusNow);
        final Timestamp parentConsensusTimestamp =
                parentConsensus != null ? HapiUtils.asTimestamp(parentConsensus) : null;

        // Sort non-empty automatic associations and pending airdrops to simplify record translation
        var newAutomaticTokenAssociations = automaticTokenAssociations;
        if (!automaticTokenAssociations.isEmpty()) {
            newAutomaticTokenAssociations = new ArrayList<>(automaticTokenAssociations);
            newAutomaticTokenAssociations.sort(TOKEN_ASSOCIATION_COMPARATOR);
        }
        var newPendingAirdropRecords = pendingAirdropRecords;
        if (!pendingAirdropRecords.isEmpty()) {
            newPendingAirdropRecords = new ArrayList<>(pendingAirdropRecords);
            newPendingAirdropRecords.sort(PENDING_AIRDROP_RECORD_COMPARATOR);
        }

        final var transactionRecord = transactionRecordBuilder
                .transactionID(transactionID)
                .receipt(transactionReceipt)
                .transactionHash(transactionHash)
                .consensusTimestamp(consensusTimestamp)
                .parentConsensusTimestamp(parentConsensusTimestamp)
                .transferList(transferList)
                .tokenTransferLists(tokenTransferLists)
                .newPendingAirdrops(newPendingAirdropRecords)
                .assessedCustomFees(assessedCustomFees)
                .automaticTokenAssociations(newAutomaticTokenAssociations)
                .paidStakingRewards(paidStakingRewards)
                .build();

        // create list of sidecar records
        List<TransactionSidecarRecord> transactionSidecarRecords = new ArrayList<>();
        if (contractStateChanges != null) {
            contractStateChanges.stream()
                    .map(pair -> new TransactionSidecarRecord(
                            transactionRecord.consensusTimestamp(),
                            pair.getValue(),
                            new OneOf<>(TransactionSidecarRecord.SidecarRecordsOneOfType.STATE_CHANGES, pair.getKey())))
                    .forEach(transactionSidecarRecords::add);
        }
        contractActions.stream()
                .map(pair -> new TransactionSidecarRecord(
                        transactionRecord.consensusTimestamp(),
                        pair.getValue(),
                        new OneOf<>(TransactionSidecarRecord.SidecarRecordsOneOfType.ACTIONS, pair.getKey())))
                .forEach(transactionSidecarRecords::add);
        contractBytecodes.stream()
                .map(pair -> new TransactionSidecarRecord(
                        transactionRecord.consensusTimestamp(),
                        pair.getValue(),
                        new OneOf<>(TransactionSidecarRecord.SidecarRecordsOneOfType.BYTECODE, pair.getKey())))
                .forEach(transactionSidecarRecords::add);

        // Log end of user transaction to transaction state log
        logEndTransactionRecord(transactionID, transactionRecord);

        return new SingleTransactionRecord(
                transaction, transactionRecord, transactionSidecarRecords, new TransactionOutputs(tokenType));
    }

    @Override
    public void nullOutSideEffectFields() {
        serialNumbers.clear();
        tokenTransferLists.clear();
        pendingAirdropRecords.clear();
        automaticTokenAssociations.clear();
        transferList = TransferList.DEFAULT;
        paidStakingRewards.clear();
        assessedCustomFees.clear();

        newTotalSupply = 0L;
        transactionFee = 0L;

        if (contractFunctionResult != null) {
            final var clearLogs =
                    contractFunctionResult.copyBuilder().logInfo(emptyList()).bloom(Bytes.EMPTY);
            if (isContractCreate) {
                transactionRecordBuilder.contractCreateResult(clearLogs);
            } else {
                transactionRecordBuilder.contractCallResult(clearLogs);
            }
        }

        contractFunctionResult = null;

        transactionReceiptBuilder.accountID((AccountID) null);

        if (isContractCreate) {
            transactionReceiptBuilder.contractID((ContractID) null);
        }

        transactionReceiptBuilder.fileID((FileID) null);
        transactionReceiptBuilder.tokenID((TokenID) null);
        if (status != IDENTICAL_SCHEDULE_ALREADY_CREATED) {
            transactionReceiptBuilder.scheduleID((ScheduleID) null);
            transactionReceiptBuilder.scheduledTransactionID((TransactionID) null);
        }
        // Note that internal contract creations are removed instead of reversed
        transactionReceiptBuilder.topicRunningHash(Bytes.EMPTY);
        transactionReceiptBuilder.newTotalSupply(0L);
        transactionReceiptBuilder.topicRunningHashVersion(0L);
        transactionReceiptBuilder.topicSequenceNumber(0L);
        transactionRecordBuilder.alias(Bytes.EMPTY);
        transactionRecordBuilder.evmAddress(Bytes.EMPTY);
    }

    @Override
    public ReversingBehavior reversingBehavior() {
        return reversingBehavior;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // base transaction data

    public RecordStreamBuilder parentConsensus(@NonNull final Instant parentConsensus) {
        this.parentConsensus = requireNonNull(parentConsensus, "parentConsensus must not be null");
        return this;
    }

    @Override
    public RecordStreamBuilder consensusTimestamp(@NonNull final Instant now) {
        this.consensusNow = requireNonNull(now, "consensus time must not be null");
        return this;
    }

    /**
     * Sets the transaction.
     *
     * @param signedTx the transaction
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder signedTx(@NonNull final SignedTransaction signedTx) {
        this.signedTx = requireNonNull(signedTx, "transaction must not be null");
        return this;
    }

    @Override
    public RecordStreamBuilder serializedSignedTx(@Nullable final Bytes serializedSignedTx) {
        this.serializedSignedTx = serializedSignedTx;
        return this;
    }

    /**
     * Gets the {@link TransactionID} that is currently set.
     *
     * @return the {@link TransactionID}
     */
    public TransactionID transactionID() {
        return transactionID;
    }

    /**
     * Sets the transaction ID.
     *
     * @param transactionID the transaction ID
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder transactionID(@NonNull final TransactionID transactionID) {
        this.transactionID = requireNonNull(transactionID, "transactionID must not be null");
        return this;
    }

    /**
     * When we update nonce on the record, we need to update the body as well with the same transactionID.
     *
     * @return the builder
     */
    @NonNull
    @Override
    public RecordStreamBuilder syncBodyIdFromRecordId() {
        final var newTransactionID = transactionID;
        final var body =
                inProgressBody().copyBuilder().transactionID(newTransactionID).build();
        this.signedTx = StreamBuilder.signedTxWith(body);
        this.serializedSignedTx = SignedTransaction.PROTOBUF.toBytes(this.signedTx);
        return this;
    }

    /**
     * Sets the memo.
     *
     * @param memo the memo
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder memo(@NonNull final String memo) {
        requireNonNull(memo, "memo must not be null");
        transactionRecordBuilder.memo(memo);
        return this;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // fields needed for TransactionRecord

    /**
     * Gets the parent consensus instant.
     *
     * @return the parent consensus instant
     */
    @Nullable
    public Instant parentConsensusTimestamp() {
        return parentConsensus;
    }

    @Override
    public long transactionFee() {
        return transactionFee;
    }

    /**
     * Sets the consensus transaction fee.
     *
     * @param transactionFee the transaction fee
     * @return the builder
     */
    @NonNull
    @Override
    public RecordStreamBuilder transactionFee(final long transactionFee) {
        this.transactionFee = transactionFee;
        this.transactionRecordBuilder.transactionFee(transactionFee);
        return this;
    }

    @Override
    public void trackExplicitRewardSituation(@NonNull final AccountID accountId) {
        if (explicitRewardReceiverIds == null) {
            explicitRewardReceiverIds = new LinkedHashSet<>();
        }
        explicitRewardReceiverIds.add(accountId);
    }

    @Override
    public Set<AccountID> explicitRewardSituationIds() {
        return explicitRewardReceiverIds != null ? explicitRewardReceiverIds : emptySet();
    }

    /**
     * Sets the body to contractCall result.
     *
     * @param contractCallResult the contractCall result
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder contractCallResult(@Nullable final ContractFunctionResult contractCallResult) {
        transactionRecordBuilder.contractCallResult(contractCallResult);
        this.contractFunctionResult = contractCallResult;
        return this;
    }

    @NonNull
    @Override
    public EthereumTransactionStreamBuilder newSenderNonce(long senderNonce) {
        return this;
    }

    @NonNull
    @Override
    public ContractCallStreamBuilder evmCallTransactionResult(@Nullable EvmTransactionResult result) {
        return this;
    }

    @NonNull
    @Override
    public ContractCreateStreamBuilder evmCreateTransactionResult(@Nullable EvmTransactionResult result) {
        return this;
    }

    @NonNull
    @Override
    public RecordStreamBuilder changedNonceInfo(@NonNull final List<ContractNonceInfo> nonceInfos) {
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder createdContractIds(@NonNull final List<ContractID> contractIds) {
        return this;
    }

    /**
     * Sets the body to contractCreateResult result.
     *
     * @param contractCreateResult the contractCreate result
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder contractCreateResult(@Nullable ContractFunctionResult contractCreateResult) {
        transactionRecordBuilder.contractCreateResult(contractCreateResult);
        this.contractFunctionResult = contractCreateResult;
        return this;
    }

    @NonNull
    @Override
    public ContractCallStreamBuilder addLogs(@NonNull final List<EvmTransactionLog> logs) {
        return this;
    }

    /**
     * Gets the transferList.
     *
     * @return transferList
     */
    @Override
    @NonNull
    public TransferList transferList() {
        return transferList;
    }

    @Override
    public void setReplayedFees(@NonNull final TransferList transferList) {
        requireNonNull(transferList);
        if (this.transferList == null || this.transferList == TransferList.DEFAULT) {
            this.transferList = transferList;
        } else {
            throw new IllegalStateException("Transfer list already set");
        }
    }

    /**
     * Sets the transferList.
     *
     * @param transferList the transferList
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder transferList(@Nullable final TransferList transferList) {
        this.transferList = transferList;
        return this;
    }

    /**
     * Sets the tokenTransferLists.
     *
     * @param tokenTransferLists the tokenTransferLists
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder tokenTransferLists(@NonNull final List<TokenTransferList> tokenTransferLists) {
        requireNonNull(tokenTransferLists, "tokenTransferLists must not be null");
        this.tokenTransferLists = tokenTransferLists;
        return this;
    }

    @Override
    public List<TokenTransferList> tokenTransferLists() {
        return tokenTransferLists;
    }

    /**
     * Adds a tokenTransferList.
     *
     * @param tokenTransferList the tokenTransferList
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder addTokenTransferList(@NonNull final TokenTransferList tokenTransferList) {
        requireNonNull(tokenTransferList, "tokenTransferList must not be null");
        tokenTransferLists.add(tokenTransferList);
        return this;
    }

    /**
     * Adds to the pending airdrop record list
     *
     * @param pendingAirdropRecord pending airdrop record
     * @return the builder
     */
    @Override
    public RecordStreamBuilder addPendingAirdrop(@NonNull final PendingAirdropRecord pendingAirdropRecord) {
        requireNonNull(pendingAirdropRecord);
        this.pendingAirdropRecords.add(pendingAirdropRecord);
        return this;
    }

    @Override
    @NonNull
    public RecordStreamBuilder tokenType(final @NonNull TokenType tokenType) {
        this.tokenType = requireNonNull(tokenType);
        return this;
    }

    /**
     * Sets the scheduleRef.
     *
     * @param scheduleRef the scheduleRef
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder scheduleRef(@NonNull final ScheduleID scheduleRef) {
        requireNonNull(scheduleRef, "scheduleRef must not be null");
        transactionRecordBuilder.scheduleRef(scheduleRef);
        return this;
    }

    /**
     * Sets the assessedCustomFees.
     *
     * @param assessedCustomFees the assessedCustomFees
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder assessedCustomFees(@NonNull final List<AssessedCustomFee> assessedCustomFees) {
        requireNonNull(assessedCustomFees, "assessedCustomFees must not be null");
        this.assessedCustomFees = assessedCustomFees;
        return this;
    }

    /**
     * Adds an assessedCustomFee.
     *
     * @param assessedCustomFee the assessedCustomFee
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder addAssessedCustomFee(@NonNull final AssessedCustomFee assessedCustomFee) {
        requireNonNull(assessedCustomFee, "assessedCustomFee must not be null");
        assessedCustomFees.add(assessedCustomFee);
        return this;
    }

    /**
     * Sets the automaticTokenAssociations.
     *
     * @param automaticTokenAssociations the automaticTokenAssociations
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder automaticTokenAssociations(
            @NonNull final List<TokenAssociation> automaticTokenAssociations) {
        requireNonNull(automaticTokenAssociations, "automaticTokenAssociations must not be null");
        this.automaticTokenAssociations = automaticTokenAssociations;
        return this;
    }

    /**
     * Adds an automaticTokenAssociation.
     *
     * @param automaticTokenAssociation the automaticTokenAssociation
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder addAutomaticTokenAssociation(@NonNull final TokenAssociation automaticTokenAssociation) {
        requireNonNull(automaticTokenAssociation, "automaticTokenAssociation must not be null");
        automaticTokenAssociations.add(automaticTokenAssociation);
        return this;
    }

    @Override
    public int getNumAutoAssociations() {
        return automaticTokenAssociations.size();
    }

    /**
     * Sets the ethereum hash.
     *
     * @param ethereumHash the ethereum hash
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder ethereumHash(@NonNull final Bytes ethereumHash) {
        requireNonNull(ethereumHash, "ethereumHash must not be null");
        transactionRecordBuilder.ethereumHash(ethereumHash);
        return this;
    }

    /**
     * Sets the paidStakingRewards.
     *
     * @param paidStakingRewards the paidStakingRewards
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder paidStakingRewards(@NonNull final List<AccountAmount> paidStakingRewards) {
        requireNonNull(paidStakingRewards, "paidStakingRewards must not be null");
        this.paidStakingRewards = paidStakingRewards;
        return this;
    }

    /**
     * Adds a paidStakingReward.
     *
     * @param paidStakingReward the paidStakingReward
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder addPaidStakingReward(@NonNull final AccountAmount paidStakingReward) {
        requireNonNull(paidStakingReward, "paidStakingReward must not be null");
        paidStakingRewards.add(paidStakingReward);
        return this;
    }

    /**
     * Sets the entropy to a given number.
     *
     * @param num number to use for entropy
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder entropyNumber(final int num) {
        transactionRecordBuilder.prngNumber(num);
        return this;
    }

    /**
     * Sets the entropy to given bytes.
     *
     * @param prngBytes bytes to use for entropy
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder entropyBytes(@NonNull final Bytes prngBytes) {
        requireNonNull(prngBytes, "The argument 'prngBytes' must not be null");
        transactionRecordBuilder.prngBytes(prngBytes);
        return this;
    }

    /**
     * Sets the EVM address.
     *
     * @param evmAddress the EVM address
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder evmAddress(@NonNull final Bytes evmAddress) {
        requireNonNull(evmAddress, "evmAddress must not be null");
        transactionRecordBuilder.evmAddress(evmAddress);
        return this;
    }

    @Override
    public @NonNull List<AssessedCustomFee> getAssessedCustomFees() {
        return assessedCustomFees;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // fields needed for TransactionReceipt

    /**
     * Sets the receipt status.
     *
     * @param status the receipt status
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder status(@NonNull final ResponseCodeEnum status) {
        this.status = requireNonNull(status, "status must not be null");
        transactionReceiptBuilder.status(status);
        return this;
    }

    /**
     * Gets the receipt status.
     *
     * @return the receipt status
     */
    @Override
    @NonNull
    public ResponseCodeEnum status() {
        return status;
    }

    /**
     * Returns if the builder has a ContractFunctionResult set.
     *
     * @return the receipt status
     */
    public boolean hasContractResult() {
        return this.contractFunctionResult != null;
    }

    public long getGasUsedForContractTxn() {
        return this.contractFunctionResult.gasUsed();
    }

    /**
     * Sets the receipt accountID.
     *
     * @param accountID the {@link AccountID} for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder accountID(@NonNull final AccountID accountID) {
        requireNonNull(accountID, "accountID must not be null");
        transactionReceiptBuilder.accountID(accountID);
        return this;
    }

    /**
     * Sets the receipt fileID.
     *
     * @param fileID the {@link FileID} for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder fileID(@NonNull final FileID fileID) {
        requireNonNull(fileID, "fileID must not be null");
        transactionReceiptBuilder.fileID(fileID);
        return this;
    }

    /**
     * Sets the receipt contractID; if the contractID is null, this is a no-op. (We allow a null id here
     * for convenience when chaining builder calls.)
     *
     * @param contractID the {@link ContractID} for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder contractID(@Nullable final ContractID contractID) {
        // Ensure we don't externalize as an account creation too
        transactionReceiptBuilder.accountID((AccountID) null);
        transactionReceiptBuilder.contractID(contractID);
        return this;
    }

    /**
     * Sets the receipt contractID;
     * This is used for HAPI and Ethereum contract creation transactions.
     *
     * @param contractID the {@link ContractID} for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder createdContractID(@Nullable final ContractID contractID) {
        isContractCreate = true;
        contractID(contractID);
        return this;
    }

    @NonNull
    @Override
    public RecordStreamBuilder createdEvmAddress(@Nullable final Bytes evmAddress) {
        return this;
    }

    /**
     * Gets the {@link ExchangeRateSet} that is currently set for the receipt.
     *
     * @return the {@link ExchangeRateSet}
     */
    @NonNull
    public ExchangeRateSet exchangeRate() {
        return exchangeRate;
    }

    /**{@inheritDoc}*/
    @NonNull
    @Override
    public RecordStreamBuilder exchangeRate(@Nullable final ExchangeRateSet exchangeRate) {
        this.exchangeRate = exchangeRate;
        return this;
    }

    @NonNull
    @Override
    public StreamBuilder congestionMultiplier(long congestionMultiplier) {
        // No-op
        return this;
    }

    /**
     * Sets the receipt topicID.
     *
     * @param topicID the {@link TopicID} for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder topicID(@NonNull final TopicID topicID) {
        requireNonNull(topicID, "topicID must not be null");
        transactionReceiptBuilder.topicID(topicID);
        return this;
    }

    /**
     * Sets the receipt topicSequenceNumber.
     *
     * @param topicSequenceNumber the topicSequenceNumber for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder topicSequenceNumber(final long topicSequenceNumber) {
        transactionReceiptBuilder.topicSequenceNumber(topicSequenceNumber);
        return this;
    }

    /**
     * Sets the receipt topicRunningHash.
     *
     * @param topicRunningHash the topicRunningHash for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder topicRunningHash(@NonNull final Bytes topicRunningHash) {
        requireNonNull(topicRunningHash, "topicRunningHash must not be null");
        transactionReceiptBuilder.topicRunningHash(topicRunningHash);
        return this;
    }

    /**
     * Sets the receipt topicRunningHashVersion.
     *
     * @param topicRunningHashVersion the topicRunningHashVersion for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder topicRunningHashVersion(final long topicRunningHashVersion) {
        transactionReceiptBuilder.topicRunningHashVersion(topicRunningHashVersion);
        return this;
    }

    /**
     * Sets the receipt tokenID.
     *
     * @param tokenID the {@link TokenID} for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder tokenID(@NonNull final TokenID tokenID) {
        requireNonNull(tokenID, "tokenID must not be null");
        this.tokenID = tokenID;
        transactionReceiptBuilder.tokenID(tokenID);
        return this;
    }

    public TokenID tokenID() {
        return tokenID;
    }

    /**
     * Sets the receipt nodeID.
     *
     * @param nodeId the nodeId for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder nodeID(long nodeId) {
        transactionReceiptBuilder.nodeId(nodeId);
        return this;
    }

    /**
     * Sets the receipt newTotalSupply.
     *
     * @param newTotalSupply the newTotalSupply for the receipt
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder newTotalSupply(final long newTotalSupply) {
        this.newTotalSupply = newTotalSupply;
        transactionReceiptBuilder.newTotalSupply(newTotalSupply);
        return this;
    }

    public long getNewTotalSupply() {
        return newTotalSupply;
    }

    /**
     * Sets the receipt scheduleID.
     *
     * @param scheduleID the {@link ScheduleID} for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder scheduleID(@NonNull final ScheduleID scheduleID) {
        requireNonNull(scheduleID, "scheduleID must not be null");
        transactionReceiptBuilder.scheduleID(scheduleID);
        this.scheduleID = requireNonNull(scheduleID);
        return this;
    }

    /**
     * Sets the transaction ID of the scheduled child transaction that was executed
     *
     * @param scheduledTransactionID the {@link TransactionID} of the scheduled transaction for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder scheduledTransactionID(@NonNull final TransactionID scheduledTransactionID) {
        transactionReceiptBuilder.scheduledTransactionID(scheduledTransactionID);
        return this;
    }

    /**
     * Sets the receipt serialNumbers.
     *
     * @param serialNumbers the serialNumbers for the receipt
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder serialNumbers(@NonNull final List<Long> serialNumbers) {
        requireNonNull(serialNumbers, "serialNumbers must not be null");
        this.serialNumbers = serialNumbers;
        return this;
    }

    @Override
    public List<Long> serialNumbers() {
        return serialNumbers;
    }

    /**
     * Adds a serialNumber to the receipt.
     *
     * @param serialNumber the serialNumber to add
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder addSerialNumber(final long serialNumber) {
        serialNumbers.add(serialNumber);
        return this;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // Sidecar data, booleans are the migration flag

    @Override
    public ContractOperationStreamBuilder testForIdenticalKeys(@NonNull final Predicate<Object> test) {
        throw new UnsupportedOperationException("Record stream uses verbose sidecars");
    }

    /**
     * Sets the contractStateChanges which are part of sidecar records.
     *
     * @param contractStateChanges the contractStateChanges
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder contractStateChanges(
            @NonNull final List<AbstractMap.SimpleEntry<ContractStateChanges, Boolean>> contractStateChanges) {
        requireNonNull(contractStateChanges, "contractStateChanges must not be null");
        this.contractStateChanges = contractStateChanges;
        return this;
    }

    @Override
    public List<StateChange> getStateChanges() {
        throw new UnsupportedOperationException("Record stream does not accumulate state changes");
    }

    /**
     * Adds contractStateChanges to sidecar records.
     *
     * @param contractStateChanges the contractStateChanges to add
     * @param isMigration flag indicating whether sidecar is from migration
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder addContractStateChanges(
            @NonNull final ContractStateChanges contractStateChanges, final boolean isMigration) {
        requireNonNull(contractStateChanges, "contractStateChanges must not be null");
        if (this.contractStateChanges == null) {
            this.contractStateChanges = new LinkedList<>();
        }
        this.contractStateChanges.add(new AbstractMap.SimpleEntry<>(contractStateChanges, isMigration));
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder addContractSlotUsages(@NonNull final List<ContractSlotUsage> slotUsages) {
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder addActions(@NonNull final List<ContractAction> actions) {
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder addInitcode(@NonNull final ExecutedInitcode initcode) {
        return this;
    }

    /**
     * Adds contractActions to sidecar records.
     *
     * @param contractActions the contractActions to add
     * @param isMigration flag indicating whether sidecar is from migration
     * @return the builder
     */
    @NonNull
    public RecordStreamBuilder addContractActions(
            @NonNull final ContractActions contractActions, final boolean isMigration) {
        requireNonNull(contractActions, "contractActions must not be null");
        this.contractActions.add(new AbstractMap.SimpleEntry<>(contractActions, isMigration));
        return this;
    }

    /**
     * Adds contractBytecodes to sidecar records.
     *
     * @param contractBytecode the contractBytecode to add
     * @param isMigration flag indicating whether sidecar is from migration
     * @return the builder
     */
    @Override
    @NonNull
    public RecordStreamBuilder addContractBytecode(
            @NonNull final ContractBytecode contractBytecode, final boolean isMigration) {
        requireNonNull(contractBytecode, "contractBytecode must not be null");
        final var entry = new AbstractMap.SimpleEntry<>(contractBytecode, isMigration);
        if (!contractBytecodes.contains(entry)) {
            contractBytecodes.add(entry);
        }
        return this;
    }

    // ------------- Information needed by token service for redirecting staking rewards to appropriate accounts

    /**
     * Adds a beneficiary for a deleted account into the map. This is needed while computing staking rewards.
     * If the deleted account receives staking reward, it is transferred to the beneficiary.
     *
     * @param deletedAccountID the deleted account ID
     * @param beneficiaryForDeletedAccount the beneficiary account ID
     */
    @Override
    public void addBeneficiaryForDeletedAccount(
            @NonNull final AccountID deletedAccountID, @NonNull final AccountID beneficiaryForDeletedAccount) {
        requireNonNull(deletedAccountID, "deletedAccountID must not be null");
        requireNonNull(beneficiaryForDeletedAccount, "beneficiaryForDeletedAccount must not be null");
        deletedAccountBeneficiaries.put(deletedAccountID, beneficiaryForDeletedAccount);
    }

    /**
     * Gets number of deleted accounts in this transaction.
     *
     * @return number of deleted accounts in this transaction
     */
    @Override
    public int getNumberOfDeletedAccounts() {
        return deletedAccountBeneficiaries.size();
    }

    /**
     * Gets the beneficiary account ID for deleted account ID.
     *
     * @return the beneficiary account ID of deleted account ID
     */
    @Override
    @Nullable
    public AccountID getDeletedAccountBeneficiaryFor(@NonNull final AccountID deletedAccountID) {
        return deletedAccountBeneficiaries.get(deletedAccountID);
    }

    /**
     * Returns the in-progress {@link ContractFunctionResult}.
     *
     * @return the in-progress {@link ContractFunctionResult}
     */
    public ContractFunctionResult contractFunctionResult() {
        return contractFunctionResult;
    }

    @Override
    public @NonNull TransactionBody transactionBody() {
        return inProgressBody();
    }

    /**
     * Returns the in-progress {@link TransactionBody}.
     *
     * @return the in-progress {@link TransactionBody}
     */
    private TransactionBody inProgressBody() {
        try {
            return TransactionBody.PROTOBUF.parse(signedTx.bodyBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Record being built for unparseable transaction", e);
        }
    }

    /**
     * Returns the staking rewards paid in this transaction.
     *
     * @return the staking rewards paid in this transaction
     */
    @Override
    @NonNull
    public List<AccountAmount> getPaidStakingRewards() {
        return paidStakingRewards;
    }

    /**
     * Returns the {@link TransactionRecord.Builder} of the record. It can be PRECEDING, CHILD, USER or SCHEDULED.
     * @return the {@link TransactionRecord.Builder} of the record
     */
    @Override
    @NonNull
    public TransactionCategory category() {
        return category;
    }

    @Override
    public StreamBuilder functionality(@NonNull final HederaFunctionality functionality) {
        this.function = functionality;
        return this;
    }

    @Override
    public ScheduleID scheduleID() {
        return scheduleID;
    }

    @Override
    public HederaFunctionality functionality() {
        return function;
    }
}
