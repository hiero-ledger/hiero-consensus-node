// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.IDENTICAL_SCHEDULE_ALREADY_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.token.impl.comparator.TokenComparators.PENDING_AIRDROP_ID_COMPARATOR;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.CallContractOutput;
import com.hedera.hapi.block.stream.output.CreateAccountOutput;
import com.hedera.hapi.block.stream.output.CreateContractOutput;
import com.hedera.hapi.block.stream.output.CreateScheduleOutput;
import com.hedera.hapi.block.stream.output.EthereumOutput;
import com.hedera.hapi.block.stream.output.SignScheduleOutput;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.output.UtilPrngOutput;
import com.hedera.hapi.block.stream.trace.ContractInitcode;
import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.EVMTraceData;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.block.stream.trace.TraceData;
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
import com.hedera.hapi.node.contract.InternalCallContext;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.PendingAirdropRecord;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActions;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.node.app.blocks.BlockItemsTranslator;
import com.hedera.node.app.blocks.impl.contexts.AirdropOpContext;
import com.hedera.node.app.blocks.impl.contexts.BaseOpContext;
import com.hedera.node.app.blocks.impl.contexts.ContractOpContext;
import com.hedera.node.app.blocks.impl.contexts.CryptoOpContext;
import com.hedera.node.app.blocks.impl.contexts.FileOpContext;
import com.hedera.node.app.blocks.impl.contexts.MintOpContext;
import com.hedera.node.app.blocks.impl.contexts.NodeOpContext;
import com.hedera.node.app.blocks.impl.contexts.ScheduleOpContext;
import com.hedera.node.app.blocks.impl.contexts.SubmitOpContext;
import com.hedera.node.app.blocks.impl.contexts.SupplyChangeOpContext;
import com.hedera.node.app.blocks.impl.contexts.TokenOpContext;
import com.hedera.node.app.blocks.impl.contexts.TopicOpContext;
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
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An implementation of {@link BlockStreamBuilder} that produces block items for a single user or
 * synthetic transaction; that is, the "input" block item with a {@link Transaction} and "output" block items
 * with a {@link TransactionResult} and, optionally, {@link TransactionOutput} and {@link TraceData}.
 */
public class BlockStreamBuilder
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
            Comparator.<TokenAssociation>comparingLong(a -> a.tokenIdOrThrow().tokenNum())
                    .thenComparingLong(a -> a.accountIdOrThrow().accountNumOrThrow());
    private static final Comparator<PendingAirdropRecord> PENDING_AIRDROP_RECORD_COMPARATOR =
            Comparator.comparing(PendingAirdropRecord::pendingAirdropIdOrThrow, PENDING_AIRDROP_ID_COMPARATOR);

    // --- Fields representing the input transaction ---
    /**
     * The transaction "owning" the stream items we are building.
     */
    private Transaction transaction;
    /**
     * If set, the serialized form of the transaction; if not set, it will be serialized from the transaction.
     * (We already have this pre-serialized when the transaction came from an event.)
     */
    @Nullable
    private Bytes serializedTransaction;

    // --- Fields used to build the TranslationContext ---
    /**
     * The functionality of the transaction, set explicitly to avoid parsing the transaction again.
     */
    @Nullable
    private HederaFunctionality functionality;
    /**
     * The exchange rate set to add to the record stream transaction receipt.
     */
    private ExchangeRateSet translationContextExchangeRates;
    /**
     * The memo from the transaction, set explicitly to avoid parsing the transaction again.
     */
    private String memo;
    /**
     * The id of the transaction, set explicitly to avoid parsing the transaction again.
     */
    private TransactionID transactionId;
    /**
     * The serial numbers minted by the transaction.
     */
    private List<Long> serialNumbers = new LinkedList<>();
    /**
     * The new total supply of a token affected by the transaction.
     */
    private long newTotalSupply = 0L;
    /**
     * The id of a node created by the transaction.
     */
    private long nodeId;
    /**
     * The id of a file created by the transaction.
     */
    private FileID fileId;
    /**
     * The id of a topic created by the transaction.
     */
    private TopicID topicId;
    /**
     * The id of a token created by the transaction.
     */
    private TokenID tokenId;
    /**
     * The id of an account created or updated by the transaction.
     */
    private AccountID accountId;
    /**
     * The id of a contract called, created, updated, or deleted by the transaction.
     */
    private ContractID contractId;
    /**
     * The sequence number of a topic receiving a message in the transaction.
     */
    private long sequenceNumber = 0L;
    /**
     * The running hash of a topic receiving a message in the transaction.
     */
    private Bytes runningHash = Bytes.EMPTY;
    /**
     * The version of the running hash of a topic receiving a message in the transaction.
     */
    private long runningHashVersion = 0L;
    /**
     * Any new pending airdrops created by the transaction.
     */
    private List<PendingAirdropRecord> pendingAirdropRecords = emptyList();
    /**
     * The EVM address of an account created by the transaction.
     */
    private Bytes evmAddress = Bytes.EMPTY;

    // --- Fields used to build the TransactionResult ---
    /**
     * The builder for the transaction result.
     */
    private final TransactionResult.Builder transactionResultBuilder = TransactionResult.newBuilder();
    /**
     * The final status of handling the transaction.
     */
    private ResponseCodeEnum status = OK;
    /**
     * The consensus time of the transaction.
     */
    private Instant consensusNow;
    /**
     * The HBAR transfers resulting from the transaction.
     */
    private TransferList transferList = TransferList.DEFAULT;
    /**
     * The token transfer lists resulting from the transaction.
     */
    private List<TokenTransferList> tokenTransferLists = new LinkedList<>();
    /**
     * The assessed custom fees resulting from the transaction.
     */
    private List<AssessedCustomFee> assessedCustomFees = new LinkedList<>();
    /**
     * The staking rewards paid as a result of the transaction.
     */
    private List<AccountAmount> paidStakingRewards = new LinkedList<>();
    /**
     * The automatic token associations resulting from the transaction.
     */
    private final List<TokenAssociation> automaticTokenAssociations = new LinkedList<>();

    // --- Fields used to build the TransactionOutput(s) ---
    /**
     * Enumerates the types of contract operations that may have a result.
     */
    private enum ContractOpType {
        /**
         * A contract creation operation.
         */
        CREATE,
        /**
         * A contract call operation.
         */
        CALL,
        /**
         * An Ethereum transaction that was throttled by gas.
         */
        ETH_THROTTLED,
        /**
         * An Ethereum transaction that created a contract.
         */
        ETH_CREATE,
        /**
         * An Ethereum transaction that called a contract.
         */
        ETH_CALL,
    }
    /**
     * The type of contract operation that was performed.
     */
    private ContractOpType contractOpType = null;

    /**
     * The result of an EVM transaction, if any.
     */
    @Nullable
    private EvmTransactionResult evmTransactionResult;

    /**
     * If set, the nonce of the signer after the transaction.
     */
    @Nullable
    private Long senderNonce;

    /**
     * If set, the ids of contracts that had their nonce changed during the call.
     */
    @Nullable
    private List<ContractNonceInfo> changedNonceInfos;

    /**
     * If set, the ids of contracts that were created in the EVM transaction.
     */
    @Nullable
    private List<ContractID> createdContractIds;

    /**
     * If set, the EVM logs resulting from the transaction.
     */
    @Nullable
    private List<EvmTransactionLog> logs;

    /**
     * If set, the contract slot usages resulting from the transaction.
     */
    @Nullable
    private List<ContractSlotUsage> slotUsages;

    /**
     * The contract actions resulting from the transaction.
     */
    @Nullable
    private List<ContractAction> contractActions;

    /**
     * Any contract initcodes used in the transaction.
     */
    @Nullable
    private List<ContractInitcode> initcodes;

    /**
     * The hash of the Ethereum payload if relevant to the transaction.
     */
    private Bytes ethereumHash = Bytes.EMPTY;
    /**
     * Whether any non-empty Ethereum transaction hash was hydrated from a file.
     */
    private boolean hydratedFromFile = false;
    /**
     * Whether the transaction creates or deletes a schedule.
     */
    private boolean createsOrDeletesSchedule;
    /**
     * The id of a scheduled transaction created or signed by the transaction.
     */
    private TransactionID scheduledTransactionId;
    /**
     * The prebuild item for a UTIL_PRNG output.
     */
    private BlockItem utilPrngOutputItem;

    // --- Fields used to either build the TranslationContext or a TransactionOutput ---
    /**
     * The id of a schedule created or deleted by the transaction.
     */
    private ScheduleID scheduleId;

    // --- Fields used to build the StateChanges items ---
    /**
     * The state changes resulting from the transaction.
     */
    private final List<StateChange> stateChanges = new ArrayList<>();

    // --- Fields used to communicate between handler logic and the HandleWorkflow ---
    /**
     * The ids of accounts that should be considered as in a "reward situation" despite the canonical
     * definition; needed for backward compatibility.
     */
    @Nullable
    private Set<AccountID> explicitRewardReceiverIds;
    /**
     * The beneficiaries of accounts deleted by the transaction.
     */
    private final Map<AccountID, AccountID> deletedAccountBeneficiaries = new HashMap<>();
    /**
     * A getter for the transaction fee set on the TransactionResult builder.
     */
    private long transactionFee;

    // --- Fields used to guide the HandleWorkflow in finalizing this builder's items in the stream ---
    /**
     * The category of the transaction.
     */
    private final HandleContext.TransactionCategory category;
    /**
     * For a child transaction, how its results should be reversed during rollback.
     */
    private final ReversingBehavior reversingBehavior;
    /**
     * How the transaction should be customized before externalization to the stream.
     */
    private final TransactionCustomizer customizer;

    /**
     * the total duration of contract operations as calculated using the Hedera ops duration schedule
     */
    private long opsDuration;

    private boolean isContractCreate;

    /**
     * Constructs a builder for a user transaction with the given characteristics.
     * @param reversingBehavior the reversing behavior
     * @param customizer the customizer
     * @param category the category
     */
    public BlockStreamBuilder(
            @NonNull final ReversingBehavior reversingBehavior,
            @NonNull final TransactionCustomizer customizer,
            @NonNull final HandleContext.TransactionCategory category) {
        this.reversingBehavior = requireNonNull(reversingBehavior);
        this.customizer = requireNonNull(customizer);
        this.category = requireNonNull(category);
    }

    /**
     * Encapsulates the output associated to a single logical {@link Transaction}, whether user or synthetic, as
     * well as the logic to translate it into a {@link TransactionRecord} or {@link TransactionReceipt} given the
     * {@link BlockItemsTranslator} to use.
     * @param blockItems the list of block items
     * @param translationContext the translation context
     */
    public record Output(@NonNull List<BlockItem> blockItems, @NonNull TranslationContext translationContext) {
        public Output {
            requireNonNull(blockItems);
            requireNonNull(translationContext);
        }

        /**
         * Exposes each {@link BlockItem} in the output to the given action.
         * @param action the action to apply
         */
        public void forEachItem(@NonNull final Consumer<BlockItem> action) {
            requireNonNull(action);
            blockItems.forEach(action);
        }

        /**
         * Translates the block items into a transaction record.
         * @param translator the translator to use
         * @return the transaction record
         */
        public TransactionRecord toRecord(@NonNull final BlockItemsTranslator translator) {
            requireNonNull(translator);
            return toView(translator, View.RECORD);
        }

        /**
         * Translates the block items into a transaction receipt.
         * @param translator the translator to use
         * @return the transaction record
         */
        public RecordSource.IdentifiedReceipt toIdentifiedReceipt(@NonNull final BlockItemsTranslator translator) {
            requireNonNull(translator);
            return toView(translator, View.RECEIPT);
        }

        /**
         * The view to translate to.
         */
        private enum View {
            RECEIPT,
            RECORD
        }

        /**
         * Uses a given translator to translate the block items into a view of the requested type. The steps are,
         * <ol>
         *     <li>Find the {@link TransactionResult} in this builder's items.</li>
         *     <li>Find the {@link TransactionOutput} items, if any.</li>
         *     <li>Translate these items into a view of the requested type.</li>
         * </ol>
         * @param translator the translator to use
         * @param view the type of view to translate to
         * @return the translated view
         * @param <T> the Java type of the view
         */
        @SuppressWarnings("unchecked")
        private <T> T toView(@NonNull final BlockItemsTranslator translator, @NonNull final View view) {
            int i = 0;
            final var n = blockItems.size();
            TransactionResult result = null;
            while (i < n && (result = blockItems.get(i++).transactionResult()) == null) {
                // Skip over non-result items
            }
            requireNonNull(result);
            if (i < n && blockItems.get(i).hasTransactionOutput()) {
                int j = i;
                while (j < n && blockItems.get(j).hasTransactionOutput()) {
                    j++;
                }
                final var outputs = new TransactionOutput[j - i];
                for (int k = i; k < j; k++) {
                    outputs[k - i] = blockItems.get(k).transactionOutput();
                }
                List<EvmTransactionLog> logs = null;
                for (final var item : blockItems.subList(j, n)) {
                    if (item.hasTraceData()) {
                        final var traceData = item.traceDataOrThrow();
                        if (traceData.hasEvmTraceData()) {
                            if (logs == null) {
                                logs = new ArrayList<>();
                            }
                            logs.addAll(traceData.evmTraceDataOrThrow().logs());
                        }
                    }
                }
                return (T)
                        switch (view) {
                            case RECEIPT ->
                                new RecordSource.IdentifiedReceipt(
                                        translationContext.txnId(),
                                        translator.translateReceipt(translationContext, result, outputs));
                            case RECORD -> translator.translateRecord(translationContext, result, logs, outputs);
                        };
            } else {
                return (T)
                        switch (view) {
                            case RECEIPT ->
                                new RecordSource.IdentifiedReceipt(
                                        translationContext.txnId(),
                                        translator.translateReceipt(translationContext, result));
                            case RECORD -> translator.translateRecord(translationContext, result, null);
                        };
            }
        }
    }

    /**
     * Builds the list of block items with their translation contexts.
     * @param topLevel if true, indicates the output should always include a following {@link StateChanges} item
     * @return the list of block items
     */
    public Output build(final boolean topLevel) {
        final var blockItems = new ArrayList<BlockItem>();
        // Construct the context here to capture any additional Ethereum transaction details needed
        // for the legacy record before they are removed from the block stream output item
        final var translationContext = translationContext();
        // Don't duplicate the transaction bytes for the batch inner transactions, since the transactions
        // can be inferred from the parent transaction.
        if (category != HandleContext.TransactionCategory.BATCH_INNER) {
            blockItems.add(BlockItem.newBuilder()
                    .eventTransaction(EventTransaction.newBuilder()
                            .applicationTransaction(getSerializedTransaction())
                            .build())
                    .build());
        }
        blockItems.add(transactionResultBlockItem());
        addOutputItemsTo(blockItems);
        if (slotUsages != null || contractActions != null || initcodes != null || logs != null) {
            final var builder = EVMTraceData.newBuilder();
            if (slotUsages != null) {
                builder.contractSlotUsages(slotUsages);
            }
            if (contractActions != null) {
                builder.contractActions(contractActions);
            }
            if (initcodes != null) {
                builder.initcodes(initcodes);
            }
            if (logs != null) {
                builder.logs(logs);
            }
            blockItems.add(BlockItem.newBuilder()
                    .traceData(TraceData.newBuilder().evmTraceData(builder))
                    .build());
        }
        if (!stateChanges.isEmpty() || topLevel) {
            blockItems.add(BlockItem.newBuilder()
                    .stateChanges(StateChanges.newBuilder()
                            .consensusTimestamp(asTimestamp(consensusNow))
                            .stateChanges(stateChanges)
                            .build())
                    .build());
        }
        return new Output(blockItems, translationContext);
    }

    @Override
    public StreamBuilder stateChanges(@NonNull List<StateChange> stateChanges) {
        this.stateChanges.addAll(stateChanges);
        return this;
    }

    @Override
    public BlockStreamBuilder functionality(@NonNull final HederaFunctionality functionality) {
        this.functionality = requireNonNull(functionality);
        return this;
    }

    @Override
    @NonNull
    public ReversingBehavior reversingBehavior() {
        return reversingBehavior;
    }

    @Override
    public int getNumAutoAssociations() {
        return automaticTokenAssociations.size();
    }

    @Override
    public HederaFunctionality functionality() {
        return functionality;
    }

    @Override
    public ScheduleID scheduleID() {
        return scheduleId;
    }

    @Override
    @NonNull
    public BlockStreamBuilder parentConsensus(@NonNull final Instant parentConsensus) {
        transactionResultBuilder.parentConsensusTimestamp(Timestamp.newBuilder()
                .seconds(parentConsensus.getEpochSecond())
                .nanos(parentConsensus.getNano())
                .build());
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder consensusTimestamp(@NonNull final Instant now) {
        this.consensusNow = requireNonNull(now, "consensus time must not be null");
        transactionResultBuilder.consensusTimestamp(Timestamp.newBuilder()
                .seconds(now.getEpochSecond())
                .nanos(now.getNano())
                .build());
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder transaction(@NonNull final Transaction transaction) {
        this.transaction = requireNonNull(transaction);
        return this;
    }

    @Override
    public StreamBuilder serializedTransaction(@Nullable final Bytes serializedTransaction) {
        this.serializedTransaction = serializedTransaction;
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder transactionBytes(@NonNull final Bytes transactionBytes) {
        return this;
    }

    @Override
    @NonNull
    public TransactionID transactionID() {
        return transactionId;
    }

    @Override
    @NonNull
    public BlockStreamBuilder transactionID(@NonNull final TransactionID transactionId) {
        this.transactionId = requireNonNull(transactionId);
        return this;
    }

    @NonNull
    @Override
    public BlockStreamBuilder syncBodyIdFromRecordId() {
        this.transaction = StreamBuilder.transactionWith(
                inProgressBody().copyBuilder().transactionID(transactionId).build());
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder memo(@NonNull final String memo) {
        this.memo = requireNonNull(memo);
        return this;
    }

    @Override
    @NonNull
    public Transaction transaction() {
        return transaction;
    }

    @Override
    public long transactionFee() {
        return transactionFee;
    }

    @NonNull
    @Override
    public BlockStreamBuilder transactionFee(final long transactionFee) {
        transactionResultBuilder.transactionFeeCharged(transactionFee);
        this.transactionFee = transactionFee;
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

    @Override
    @NonNull
    public BlockStreamBuilder contractCallResult(@Nullable final ContractFunctionResult contractCallResult) {
        throw new UnsupportedOperationException("Use concise EVM transaction result");
    }

    @NonNull
    @Override
    public EthereumTransactionStreamBuilder newSenderNonce(final long senderNonce) {
        this.senderNonce = senderNonce;
        return this;
    }

    @NonNull
    @Override
    public BlockStreamBuilder changedNonceInfo(@NonNull final List<ContractNonceInfo> nonceInfos) {
        this.changedNonceInfos = requireNonNull(nonceInfos);
        return this;
    }

    @NonNull
    @Override
    public ContractOperationStreamBuilder createdContractIds(@NonNull final List<ContractID> contractIds) {
        this.createdContractIds = requireNonNull(contractIds);
        return this;
    }

    @NonNull
    @Override
    public BlockStreamBuilder evmCallTransactionResult(@Nullable final EvmTransactionResult result) {
        this.evmTransactionResult = result;
        if (result != null) {
            if (contractOpType != ContractOpType.ETH_THROTTLED) {
                contractOpType = ContractOpType.CALL;
            } else {
                contractOpType = ContractOpType.ETH_CALL;
            }
        }
        return this;
    }

    @NonNull
    @Override
    public ContractCallStreamBuilder addLogs(@NonNull final List<EvmTransactionLog> logs) {
        this.logs = requireNonNull(logs);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder contractCreateResult(@Nullable ContractFunctionResult contractCreateResult) {
        throw new UnsupportedOperationException("Use concise EVM transaction result");
    }

    @NonNull
    @Override
    public ContractCreateStreamBuilder evmCreateTransactionResult(@Nullable final EvmTransactionResult result) {
        this.evmTransactionResult = result;
        if (result != null) {
            if (contractOpType != ContractOpType.ETH_THROTTLED) {
                contractOpType = ContractOpType.CREATE;
            } else {
                contractOpType = ContractOpType.ETH_CREATE;
            }
        }
        return this;
    }

    @Override
    @NonNull
    public TransferList transferList() {
        return transferList;
    }

    @Override
    @NonNull
    public BlockStreamBuilder transferList(@Nullable final TransferList transferList) {
        this.transferList = transferList;
        return this;
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

    @Override
    @NonNull
    public BlockStreamBuilder tokenTransferLists(@NonNull final List<TokenTransferList> tokenTransferLists) {
        this.tokenTransferLists = requireNonNull(tokenTransferLists);
        transactionResultBuilder.tokenTransferLists(tokenTransferLists);
        return this;
    }

    @Override
    public List<TokenTransferList> tokenTransferLists() {
        return tokenTransferLists;
    }

    @Override
    @NonNull
    public BlockStreamBuilder tokenType(final @NonNull TokenType tokenType) {
        return this;
    }

    @Override
    public BlockStreamBuilder addPendingAirdrop(@NonNull final PendingAirdropRecord pendingAirdropRecord) {
        requireNonNull(pendingAirdropRecord);
        if (pendingAirdropRecords.isEmpty()) {
            pendingAirdropRecords = new LinkedList<>();
        }
        pendingAirdropRecords.add(pendingAirdropRecord);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder scheduleRef(@NonNull final ScheduleID scheduleRef) {
        requireNonNull(scheduleRef, "scheduleRef must not be null");
        transactionResultBuilder.scheduleRef(scheduleRef);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder assessedCustomFees(@NonNull final List<AssessedCustomFee> assessedCustomFees) {
        this.assessedCustomFees = requireNonNull(assessedCustomFees);
        return this;
    }

    @NonNull
    public BlockStreamBuilder addAutomaticTokenAssociation(@NonNull final TokenAssociation automaticTokenAssociation) {
        requireNonNull(automaticTokenAssociation, "automaticTokenAssociation must not be null");
        automaticTokenAssociations.add(automaticTokenAssociation);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder ethereumHash(@NonNull final Bytes ethereumHash, final boolean hydratedFromFile) {
        contractOpType = ContractOpType.ETH_THROTTLED;
        this.ethereumHash = requireNonNull(ethereumHash);
        this.hydratedFromFile = hydratedFromFile;
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder paidStakingRewards(@NonNull final List<AccountAmount> paidStakingRewards) {
        requireNonNull(paidStakingRewards);
        this.paidStakingRewards = paidStakingRewards;
        transactionResultBuilder.paidStakingRewards(paidStakingRewards);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder entropyNumber(final int num) {
        utilPrngOutputItem = itemWith(TransactionOutput.newBuilder()
                .utilPrng(UtilPrngOutput.newBuilder().prngNumber(num).build()));
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder entropyBytes(@NonNull final Bytes prngBytes) {
        requireNonNull(prngBytes);
        utilPrngOutputItem = itemWith(TransactionOutput.newBuilder()
                .utilPrng(UtilPrngOutput.newBuilder().prngBytes(prngBytes).build()));
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder evmAddress(@NonNull final Bytes evmAddress) {
        this.evmAddress = requireNonNull(evmAddress);
        return this;
    }

    @Override
    @NonNull
    public List<AssessedCustomFee> getAssessedCustomFees() {
        return assessedCustomFees;
    }

    @Override
    @NonNull
    public BlockStreamBuilder status(@NonNull final ResponseCodeEnum status) {
        this.status = requireNonNull(status);
        transactionResultBuilder.status(status);
        return this;
    }

    @Override
    @NonNull
    public ResponseCodeEnum status() {
        return status;
    }

    @Override
    public boolean hasContractResult() {
        return this.evmTransactionResult != null;
    }

    @Override
    public long getGasUsedForContractTxn() {
        return requireNonNull(this.evmTransactionResult).gasUsed();
    }

    @Override
    public long getOpsDurationForContractTxn() {
        return opsDuration;
    }

    @Override
    @NonNull
    public BlockStreamBuilder accountID(@Nullable final AccountID accountID) {
        this.accountId = accountID;
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder fileID(@NonNull final FileID fileID) {
        this.fileId = fileID;
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder contractID(@Nullable final ContractID contractID) {
        this.contractId = contractID;
        this.accountId = null;
        return this;
    }

    /**
     * Sets the receipt contractID;
     * This is used for HAPI and Ethereum contract creation transactions.
     *
     * @param contractID the {@link ContractID} for the receipt
     * @return the builder
     */
    @NonNull
    @Override
    public BlockStreamBuilder createdContractID(@Nullable ContractID contractID) {
        this.isContractCreate = true;
        contractID(contractID);
        return this;
    }

    @NonNull
    @Override
    public ContractCreateStreamBuilder createdEvmAddress(@Nullable Bytes evmAddress) {
        if (evmAddress != null) {
            this.evmAddress = requireNonNull(evmAddress);
        }
        return this;
    }

    @NonNull
    @Override
    public BlockStreamBuilder exchangeRate(@Nullable final ExchangeRateSet exchangeRate) {
        // Block Stream doesn't include exchange rate in output (it's in state
        // changes when it is updated), so store exchange rate for the
        // translation context
        translationContextExchangeRates = exchangeRate;
        return this;
    }

    @NonNull
    @Override
    public BlockStreamBuilder congestionMultiplier(final long congestionMultiplier) {
        transactionResultBuilder.congestionPricingMultiplier(congestionMultiplier);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder topicID(@NonNull final TopicID topicID) {
        this.topicId = requireNonNull(topicID);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder topicSequenceNumber(final long topicSequenceNumber) {
        this.sequenceNumber = topicSequenceNumber;
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder topicRunningHash(@NonNull final Bytes topicRunningHash) {
        this.runningHash = requireNonNull(topicRunningHash);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder topicRunningHashVersion(final long topicRunningHashVersion) {
        this.runningHashVersion = topicRunningHashVersion;
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder tokenID(@NonNull final TokenID tokenId) {
        this.tokenId = requireNonNull(tokenId);
        return this;
    }

    @Override
    public TokenID tokenID() {
        return tokenId;
    }

    @Override
    @NonNull
    public BlockStreamBuilder nodeID(final long nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    @NonNull
    public BlockStreamBuilder newTotalSupply(final long newTotalSupply) {
        this.newTotalSupply = newTotalSupply;
        return this;
    }

    @Override
    public long getNewTotalSupply() {
        return newTotalSupply;
    }

    @Override
    @NonNull
    public BlockStreamBuilder scheduleID(@NonNull final ScheduleID scheduleID) {
        this.createsOrDeletesSchedule = true;
        this.scheduleId = requireNonNull(scheduleID);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder scheduledTransactionID(@NonNull final TransactionID scheduledTransactionID) {
        this.scheduledTransactionId = requireNonNull(scheduledTransactionID);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder serialNumbers(@NonNull final List<Long> serialNumbers) {
        requireNonNull(serialNumbers, "serialNumbers must not be null");
        this.serialNumbers = serialNumbers;
        return this;
    }

    @Override
    @NonNull
    public List<Long> serialNumbers() {
        return serialNumbers;
    }

    @Override
    @NonNull
    public BlockStreamBuilder addContractStateChanges(
            @NonNull final ContractStateChanges contractStateChanges, final boolean isMigration) {
        throw new UnsupportedOperationException("Add slot usages directly");
    }

    @NonNull
    @Override
    public BlockStreamBuilder addContractSlotUsages(@NonNull final List<ContractSlotUsage> slotUsages) {
        requireNonNull(slotUsages);
        this.slotUsages = slotUsages;
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder addContractActions(
            @NonNull final ContractActions contractActions, final boolean isMigration) {
        throw new UnsupportedOperationException("Add actions directly");
    }

    @NonNull
    @Override
    public BlockStreamBuilder addActions(@NonNull final List<ContractAction> actions) {
        this.contractActions = requireNonNull(actions);
        return this;
    }

    @Override
    @NonNull
    public BlockStreamBuilder addContractBytecode(
            @NonNull final ContractBytecode contractBytecode, final boolean isMigration) {
        throw new UnsupportedOperationException("Add initcode directly");
    }

    @NonNull
    @Override
    public BlockStreamBuilder addInitcode(@NonNull final ContractInitcode initcode) {
        requireNonNull(initcode);
        if (initcodes == null) {
            initcodes = new LinkedList<>();
        }
        initcodes.add(initcode);
        return this;
    }

    @Override
    public void addBeneficiaryForDeletedAccount(
            @NonNull final AccountID deletedAccountID, @NonNull final AccountID beneficiaryForDeletedAccount) {
        requireNonNull(deletedAccountID, "deletedAccountID must not be null");
        requireNonNull(beneficiaryForDeletedAccount, "beneficiaryForDeletedAccount must not be null");
        deletedAccountBeneficiaries.put(deletedAccountID, beneficiaryForDeletedAccount);
    }

    @Override
    public int getNumberOfDeletedAccounts() {
        return deletedAccountBeneficiaries.size();
    }

    @Override
    @Nullable
    public AccountID getDeletedAccountBeneficiaryFor(@NonNull final AccountID deletedAccountID) {
        return deletedAccountBeneficiaries.get(deletedAccountID);
    }

    @Override
    @NonNull
    public TransactionBody transactionBody() {
        return inProgressBody();
    }

    @NonNull
    @Override
    public List<AccountAmount> getPaidStakingRewards() {
        return paidStakingRewards;
    }

    @Override
    @NonNull
    public HandleContext.TransactionCategory category() {
        return category;
    }

    @Override
    public void nullOutSideEffectFields() {
        serialNumbers.clear();
        if (!tokenTransferLists.isEmpty()) {
            tokenTransferLists.clear();
        }
        if (!pendingAirdropRecords.isEmpty()) {
            pendingAirdropRecords.clear();
        }
        automaticTokenAssociations.clear();
        transferList = TransferList.DEFAULT;
        paidStakingRewards.clear();
        assessedCustomFees.clear();

        newTotalSupply = 0L;
        transactionFee = 0L;

        accountId = null;
        if (isContractCreate) {
            contractId = null;
        }
        fileId = null;
        tokenId = null;
        topicId = null;
        nodeId = 0L;
        if (status != IDENTICAL_SCHEDULE_ALREADY_CREATED) {
            scheduleId = null;
            scheduledTransactionId = null;
        }

        evmAddress = Bytes.EMPTY;
        runningHash = Bytes.EMPTY;
        sequenceNumber = 0L;
        runningHashVersion = 0L;
    }

    @NonNull
    private BlockItem transactionResultBlockItem() {
        if (!automaticTokenAssociations.isEmpty()) {
            automaticTokenAssociations.sort(TOKEN_ASSOCIATION_COMPARATOR);
            transactionResultBuilder.automaticTokenAssociations(automaticTokenAssociations);
        }
        if (!assessedCustomFees.isEmpty()) {
            transactionResultBuilder.assessedCustomFees(assessedCustomFees);
        }
        return BlockItem.newBuilder()
                .transactionResult(
                        transactionResultBuilder.transferList(transferList).build())
                .build();
    }

    private TransactionBody inProgressBody() {
        try {
            final var signedTransaction = SignedTransaction.PROTOBUF.parseStrict(
                    transaction.signedTransactionBytes().toReadableSequentialData());
            return TransactionBody.PROTOBUF.parse(signedTransaction.bodyBytes().toReadableSequentialData());
        } catch (Exception e) {
            throw new IllegalStateException("Record being built for unparseable transaction", e);
        }
    }

    private void addOutputItemsTo(@NonNull final List<BlockItem> items) {
        if (utilPrngOutputItem != null) {
            items.add(utilPrngOutputItem);
        }
        if (evmTransactionResult != null || ethereumHash.length() > 0) {
            final var builder = TransactionOutput.newBuilder();
            switch (requireNonNull(contractOpType)) {
                case CREATE ->
                    builder.contractCreate(CreateContractOutput.newBuilder()
                            .evmTransactionResult(evmTransactionResult)
                            .build());
                case CALL ->
                    builder.contractCall(CallContractOutput.newBuilder()
                            .evmTransactionResult(evmTransactionResult)
                            .build());
                case ETH_CALL ->
                    builder.ethereumCall(ethOutputBuilder()
                            .evmCallTransactionResult(ethEvmTransactionResult())
                            .build());
                case ETH_CREATE ->
                    builder.ethereumCall(ethOutputBuilder()
                            .evmCreateTransactionResult(ethEvmTransactionResult())
                            .build());
                case ETH_THROTTLED -> builder.ethereumCall(ethOutputBuilder().build());
            }
            items.add(itemWith(builder));
        }
        if (createsOrDeletesSchedule && scheduledTransactionId != null) {
            items.add(itemWith(TransactionOutput.newBuilder()
                    .createSchedule(CreateScheduleOutput.newBuilder()
                            .scheduleId(scheduleId)
                            .scheduledTransactionId(scheduledTransactionId)
                            .build())));
        } else if (scheduledTransactionId != null) {
            items.add(itemWith(TransactionOutput.newBuilder()
                    .signSchedule(SignScheduleOutput.newBuilder()
                            .scheduledTransactionId(scheduledTransactionId)
                            .build())));
        }

        if (functionality == CRYPTO_CREATE && accountId != null) {
            items.add(itemWith(TransactionOutput.newBuilder()
                    .accountCreate(CreateAccountOutput.newBuilder()
                            .createdAccountId(accountId)
                            .build())));
        }
    }

    /**
     * If the Ethereum call data was hydrated from a file, and we have the function parameters available,
     * returns a copy of the {@link EvmTransactionResult} with the internal call context cleared except for
     * the call data.
     * <p>
     * Otherwise returns a {@link EvmTransactionResult} with its internal context cleared.
     * @return the {@link EvmTransactionResult} appropriate for an {@link EthereumOutput} item
     */
    private @Nullable EvmTransactionResult ethEvmTransactionResult() {
        if (evmTransactionResult == null) {
            return null;
        }
        if (!evmTransactionResult.hasInternalCallContext()) {
            return evmTransactionResult;
        }
        final var externalizedContext = !hydratedFromFile
                ? null
                : new InternalCallContext(
                        0, 0, evmTransactionResult.internalCallContextOrThrow().callData());
        return evmTransactionResult
                .copyBuilder()
                .internalCallContext(externalizedContext)
                .build();
    }

    private EthereumOutput.Builder ethOutputBuilder() {
        final var builder = EthereumOutput.newBuilder();
        if (hydratedFromFile) {
            builder.ethereumHash(ethereumHash);
        }
        return builder;
    }

    private Bytes getSerializedTransaction() {
        if (customizer != null) {
            transaction = customizer.apply(transaction);
            return Transaction.PROTOBUF.toBytes(transaction);
        }
        return serializedTransaction != null ? serializedTransaction : Transaction.PROTOBUF.toBytes(transaction);
    }

    private BlockItem itemWith(@NonNull final TransactionOutput.Builder output) {
        return BlockItem.newBuilder().transactionOutput(output).build();
    }

    /**
     * Returns the {@link TranslationContext} that will be needed to easily translate this builder's items into
     * a {@link TransactionRecord} or {@link TransactionReceipt} if needed to answer a query.
     * @return the translation context
     */
    private TranslationContext translationContext() {
        return switch (requireNonNull(functionality)) {
            case CONTRACT_CALL, CONTRACT_CREATE, CONTRACT_DELETE, CONTRACT_UPDATE, ETHEREUM_TRANSACTION ->
                new ContractOpContext(
                        memo,
                        translationContextExchangeRates,
                        transactionId,
                        transaction,
                        functionality,
                        contractId,
                        evmAddress.length() > 0 ? evmAddress : null,
                        changedNonceInfos,
                        createdContractIds,
                        senderNonce,
                        evmTransactionResult == null ? null : evmTransactionResult.internalCallContext(),
                        ethereumHash);
            case CRYPTO_CREATE, CRYPTO_UPDATE ->
                new CryptoOpContext(
                        memo,
                        translationContextExchangeRates,
                        transactionId,
                        transaction,
                        functionality,
                        accountId,
                        evmAddress);
            case FILE_CREATE ->
                new FileOpContext(
                        memo, translationContextExchangeRates, transactionId, transaction, functionality, fileId);
            case NODE_CREATE ->
                new NodeOpContext(
                        memo, translationContextExchangeRates, transactionId, transaction, functionality, nodeId);
            case SCHEDULE_DELETE ->
                new ScheduleOpContext(
                        memo, translationContextExchangeRates, transactionId, transaction, functionality, scheduleId);
            case CONSENSUS_SUBMIT_MESSAGE ->
                new SubmitOpContext(
                        memo,
                        translationContextExchangeRates,
                        transactionId,
                        transaction,
                        functionality,
                        runningHash,
                        runningHashVersion,
                        sequenceNumber);
            case TOKEN_AIRDROP -> {
                if (!pendingAirdropRecords.isEmpty()) {
                    pendingAirdropRecords.sort(PENDING_AIRDROP_RECORD_COMPARATOR);
                }
                yield new AirdropOpContext(
                        memo,
                        translationContextExchangeRates,
                        transactionId,
                        transaction,
                        functionality,
                        pendingAirdropRecords);
            }
            case TOKEN_MINT ->
                new MintOpContext(
                        memo,
                        translationContextExchangeRates,
                        transactionId,
                        transaction,
                        functionality,
                        serialNumbers,
                        newTotalSupply);
            case TOKEN_BURN, TOKEN_ACCOUNT_WIPE ->
                new SupplyChangeOpContext(
                        memo,
                        translationContextExchangeRates,
                        transactionId,
                        transaction,
                        functionality,
                        newTotalSupply);
            case TOKEN_CREATE ->
                new TokenOpContext(
                        memo, translationContextExchangeRates, transactionId, transaction, functionality, tokenId);
            case CONSENSUS_CREATE_TOPIC ->
                new TopicOpContext(
                        memo, translationContextExchangeRates, transactionId, transaction, functionality, topicId);
            default ->
                new BaseOpContext(memo, translationContextExchangeRates, transactionId, transaction, functionality);
        };
    }
}
