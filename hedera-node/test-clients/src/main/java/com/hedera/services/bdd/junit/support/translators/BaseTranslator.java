// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_CONTRACT_BYTECODE;
import static com.hedera.hapi.node.base.HederaFunctionality.ATOMIC_BATCH;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.util.HapiUtils.CONTRACT_ID_COMPARATOR;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.EntityType.ACCOUNT;
import static com.hedera.node.app.hapi.utils.EntityType.FILE;
import static com.hedera.node.app.hapi.utils.EntityType.NODE;
import static com.hedera.node.app.hapi.utils.EntityType.SCHEDULE;
import static com.hedera.node.app.hapi.utils.EntityType.TOKEN;
import static com.hedera.node.app.hapi.utils.EntityType.TOPIC;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asBesuLog;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitAddressOf;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.scheduledTxnIdFrom;
import static com.hedera.services.bdd.junit.support.translators.impl.FileUpdateTranslator.EXCHANGE_RATES_FILE_NUM;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateIdentifier;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.PendingAirdropValue;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.ContractLoginfo;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.PendingAirdropRecord;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.ContractActions;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.hapi.streams.ContractStateChange;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.hapi.streams.StorageChange;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.log.Log;

/**
 * Implements shared translation logic for transaction records, maintaining all the extra-stream
 * context needed to recover the traditional record stream from a block stream.
 */
public class BaseTranslator {
    private static final Logger log = LogManager.getLogger(BaseTranslator.class);

    private static final Comparator<ContractID> CONTRACT_ID_NUM_COMPARATOR =
            Comparator.comparingLong(ContractID::contractNumOrThrow);
    private static final Comparator<ContractNonceInfo> NONCE_INFO_CONTRACT_ID_COMPARATOR =
            Comparator.comparing(ContractNonceInfo::contractIdOrThrow, CONTRACT_ID_NUM_COMPARATOR);

    /**
     * These fields are context maintained for the full lifetime of the translator.
     */
    private long highestKnownEntityNum = 0L;

    private long highestKnownNodeId =
            -1L; // Default to negative value so that we allow for nodeId with 0 value to be created

    private boolean externalizeNonces = true;

    private ExchangeRateSet activeRates;
    private final Map<Long, Long> nonces = new HashMap<>();
    private final Map<Long, Address> evmAddresses = new HashMap<>();
    private final Map<TokenID, Long> totalSupplies = new HashMap<>();
    private final Map<TokenID, TokenType> tokenTypes = new HashMap<>();
    private final Map<TransactionID, ScheduleID> scheduleRefs = new HashMap<>();
    private final Map<ScheduleID, TransactionID> scheduleTxnIds = new HashMap<>();
    private final Set<TokenAssociation> knownAssociations = new HashSet<>();
    private final Map<PendingAirdropId, PendingAirdropValue> pendingAirdrops = new HashMap<>();

    /**
     * These fields are used to translate a single "unit" of block items connected to a {@link TransactionID}.
     */
    private long prevHighestKnownEntityNum = 0L;

    private Instant userTimestamp;
    private final Map<TokenID, Integer> numMints = new HashMap<>();
    private final Map<TokenID, List<Long>> highestPutSerialNos = new HashMap<>();
    private final Map<EntityType, List<Long>> nextCreatedNums = new EnumMap<>(EntityType.class);
    private final Set<ScheduleID> purgedScheduleIds = new HashSet<>();

    /**
     * Defines how a translator specifies details of a translated transaction record.
     */
    @FunctionalInterface
    public interface Spec {
        void accept(
                @NonNull TransactionReceipt.Builder receiptBuilder, @NonNull TransactionRecord.Builder recordBuilder);
    }

    /**
     * Constructs a base translator.
     */
    public BaseTranslator() {
        // Using default field values
    }

    /**
     * Scans a block for genesis information and returns true if found.
     *
     * @param block the block to scan
     * @return true if genesis information was found
     */
    public boolean scanMaybeGenesisBlock(@NonNull final Block block) {
        for (final var item : block.items()) {
            if (item.hasStateChanges()) {
                for (final var change : item.stateChangesOrThrow().stateChanges()) {
                    if (change.hasMapUpdate()
                            && change.mapUpdateOrThrow().keyOrThrow().hasFileIdKey()) {
                        final var fileNum = change.mapUpdateOrThrow()
                                .keyOrThrow()
                                .fileIdKeyOrThrow()
                                .fileNum();
                        if (fileNum == EXCHANGE_RATES_FILE_NUM) {
                            updateActiveRates(change);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Provides the token type for the given token ID.
     *
     * @param tokenId the token ID to query
     * @return the token type
     */
    public @NonNull TokenType tokenTypeOrThrow(@NonNull final TokenID tokenId) {
        return tokenTypes.get(tokenId);
    }

    /**
     * Detects new token types from the given state changes.
     *
     * @param unit the unit to prepare for
     */
    public void prepareForUnit(@NonNull final BlockTransactionalUnit unit) {
        this.prevHighestKnownEntityNum = highestKnownEntityNum;
        numMints.clear();
        highestPutSerialNos.clear();
        nextCreatedNums.clear();
        purgedScheduleIds.clear();
        scanUnit(unit);
        nextCreatedNums.values().forEach(list -> {
            final Set<Long> distinctNums = Set.copyOf(list);
            list.clear();
            list.addAll(distinctNums);
            list.sort(Comparator.naturalOrder());
        });
        highestPutSerialNos.forEach((tokenId, serialNos) -> {
            final Set<Long> distinctSerialNos = Set.copyOf(serialNos);
            final var mintedHere = new ArrayList<>(distinctSerialNos);
            mintedHere.sort(Collections.reverseOrder());
            serialNos.clear();
            serialNos.addAll(mintedHere.subList(0, numMints.getOrDefault(tokenId, 0)));
            serialNos.sort(Comparator.naturalOrder());
        });
        if (nextCreatedNums.containsKey(NODE)) {
            highestKnownNodeId = nextCreatedNums.get(NODE).getLast();
        }
        highestKnownEntityNum =
                nextCreatedNums.values().stream().mapToLong(List::getLast).max().orElse(highestKnownEntityNum);
    }

    /**
     * Finishes the ongoing transactional unit, purging any schedules that were deleted.
     */
    public void finishLastUnit() {
        purgedScheduleIds.forEach(scheduleId -> scheduleRefs.remove(scheduleTxnIds.remove(scheduleId)));
    }

    /**
     * Determines if the given number was created in the ongoing transactional unit.
     *
     * @param num the number to query
     * @return true if the number was created
     */
    public boolean entityCreatedThisUnit(final long num) {
        return num > prevHighestKnownEntityNum;
    }

    /**
     * Tracks the association of a token with an account.
     *
     * @param tokenID the token to track
     * @param accountID the account to track
     */
    public void trackAssociation(@NonNull final TokenID tokenID, @NonNull final AccountID accountID) {
        knownAssociations.add(new TokenAssociation(tokenID, accountID));
    }

    /**
     * Tracks the dissociation of a token from an account.
     *
     * @param tokenID the token to track
     * @param accountID the account to track
     */
    public void trackDissociation(@NonNull final TokenID tokenID, @NonNull final AccountID accountID) {
        knownAssociations.add(new TokenAssociation(tokenID, accountID));
    }

    /**
     * Initializes the total supply of the given token.
     *
     * @param tokenId the token to initialize
     * @param totalSupply the total supply to set
     */
    public void initTotalSupply(@NonNull final TokenID tokenId, final long totalSupply) {
        totalSupplies.put(tokenId, totalSupply);
    }

    /**
     * Adjusts the total supply of the given token by the given amount and returns the new total supply.
     *
     * @param tokenId the token to adjust
     * @param adjustment the amount to adjust by
     * @return the new total supply
     */
    public long newTotalSupply(@NonNull final TokenID tokenId, final long adjustment) {
        return totalSupplies.merge(tokenId, adjustment, Long::sum);
    }

    /**
     * Determines if the given token was already associated with the given account before the ongoing
     * transactional unit being translated into records.
     *
     * @param tokenId the token to query
     * @param accountId the account to query
     * @return true if the token was already associated with the account
     */
    public boolean wasAlreadyAssociated(@NonNull final TokenID tokenId, @NonNull final AccountID accountId) {
        requireNonNull(tokenId);
        requireNonNull(accountId);
        return knownAssociations.contains(new TokenAssociation(tokenId, accountId));
    }

    /**
     * Provides the next {@code n} serial numbers that were minted for the given token in the transactional unit.
     *
     * @param tokenId the token to query
     * @param n the number of serial numbers to provide
     * @return the next {@code n} serial numbers that were minted for the token
     */
    public List<Long> nextNMints(@NonNull final TokenID tokenId, final int n) {
        final var serialNos = highestPutSerialNos.get(tokenId);
        if (serialNos == null) {
            log.error("No serial numbers found for token {}", tokenId);
            return emptyList();
        }
        if (n > serialNos.size()) {
            log.error("Only {} serial numbers found for token {}, not the requested {}", serialNos.size(), tokenId, n);
            return emptyList();
        }
        final var mints = new ArrayList<>(serialNos.subList(0, n));
        serialNos.removeAll(mints);
        return mints;
    }

    /**
     * Provides the next created entity number of the given type in the ongoing transactional unit.
     *
     * @param type the type of entity
     * @return the next created entity number
     */
    public long nextCreatedNum(@NonNull final EntityType type) {
        final var createdNums = nextCreatedNums.getOrDefault(type, Collections.emptyList());
        if (createdNums.isEmpty()) {
            log.error("No created numbers found for entity type {}", type);
            return -1L;
        }
        return nextCreatedNums.get(type).removeFirst();
    }

    /**
     * Tracks the given pending airdrop record if it was not already in the set of known pending airdrops.
     *
     * @param pendingAirdropRecord the pending airdrop record to track
     * @return true if the record was tracked
     */
    public boolean track(@NonNull final PendingAirdropRecord pendingAirdropRecord) {
        final var airdropId = pendingAirdropRecord.pendingAirdropIdOrThrow();
        final var currentValue = pendingAirdrops.get(airdropId);
        final var newValue = pendingAirdropRecord.pendingAirdropValue();
        final var changed = !pendingAirdrops.containsKey(airdropId) || !Objects.equals(currentValue, newValue);
        pendingAirdrops.put(airdropId, newValue);
        return changed;
    }

    /**
     * Removes the given pending airdrop record from the set of known pending airdrops.
     *
     * @param pendingAirdropId the id to remove
     */
    public void remove(@NonNull final PendingAirdropId pendingAirdropId) {
        pendingAirdrops.remove(pendingAirdropId);
    }

    /**
     * Adds the created IDs from the given state changes to the provided {@link ContractFunctionResult.Builder}.
     * @param resultBuilder the builder to populate with created IDs
     * @param stateChanges the state changes to process
     */
    public void addCreatedIdsTo(
            @NonNull final ContractFunctionResult.Builder resultBuilder,
            @NonNull final List<StateChange> stateChanges) {
        requireNonNull(resultBuilder);
        requireNonNull(stateChanges);
        final var createdIds = stateChanges.stream()
                .filter(change -> change.stateId() == STATE_ID_CONTRACT_BYTECODE.protoOrdinal())
                .filter(StateChange::hasMapUpdate)
                .map(StateChange::mapUpdateOrThrow)
                .map(MapUpdateChange::keyOrThrow)
                .map(MapChangeKey::contractIdKeyOrThrow)
                .sorted(CONTRACT_ID_COMPARATOR)
                .toList();
        resultBuilder.createdContractIDs(createdIds);
    }

    /**
     * Adds the created IDs from the given state changes to the provided {@link ContractFunctionResult.Builder}.
     *
     * @param resultBuilder the builder to populate with created IDs
     * @param stateChanges the state changes to process
     */
    public void addCreatedEvmAddressTo(
            @NonNull final ContractFunctionResult.Builder resultBuilder,
            @NonNull final ContractID contractId,
            @NonNull final List<StateChange> stateChanges) {
        requireNonNull(resultBuilder);
        requireNonNull(stateChanges);
        requireNonNull(contractId);
        final var createdContract = findContractOrThrow(contractId, stateChanges);
        final var evmAddress = Bytes.wrap(explicitAddressOf(createdContract));
        resultBuilder.evmAddress(evmAddress);
    }

    public void addSignerNonce(
            @Nullable final AccountID senderId,
            @NonNull final ContractFunctionResult.Builder derivedBuilder,
            @NonNull final List<StateChange> remainingStateChanges) {
        if (senderId != null) {
            findAccount(senderId, remainingStateChanges)
                    .ifPresentOrElse(
                            senderAccount -> derivedBuilder.signerNonce(senderAccount.ethereumNonce()),
                            () -> derivedBuilder.signerNonce(nonces.get(senderId.accountNumOrElse(Long.MIN_VALUE))));
        }
    }

    public void toggleNoncesExternalization(final boolean externalizeNonces) {
        this.externalizeNonces = externalizeNonces;
    }

    /**
     * Adds the created IDs from the given state changes to the provided {@link ContractFunctionResult.Builder}.
     * @param resultBuilder the builder to populate with created IDs
     * @param stateChanges the state changes to process
     */
    public void addChangedContractNonces(
            @NonNull final ContractFunctionResult.Builder resultBuilder,
            @NonNull final List<StateChange> stateChanges) {
        requireNonNull(resultBuilder);
        requireNonNull(stateChanges);
        if (!externalizeNonces) {
            return;
        }
        final List<ContractNonceInfo> changedNonces = new ArrayList<>();
        stateChanges.stream()
                .filter(StateChange::hasMapUpdate)
                .map(StateChange::mapUpdateOrThrow)
                .filter(change -> change.valueOrThrow().hasAccountValue())
                .map(change -> change.valueOrThrow().accountValueOrThrow())
                .filter(Account::smartContract)
                .forEach(contract -> {
                    final var contractId = contract.accountIdOrThrow();
                    if (!contract.deleted()
                            && contract.ethereumNonce() != nonces.getOrDefault(contractId.accountNumOrThrow(), 0L)) {
                        changedNonces.add(new ContractNonceInfo(
                                ContractID.newBuilder()
                                        .shardNum(contractId.shardNum())
                                        .realmNum(contractId.realmNum())
                                        .contractNum(contractId.accountNumOrThrow())
                                        .build(),
                                contract.ethereumNonce()));
                    }
                });
        changedNonces.sort(NONCE_INFO_CONTRACT_ID_COMPARATOR);
        resultBuilder.contractNonces(changedNonces);
    }

    /**
     * Given a {@link BlockTransactionParts} and a {@link Spec}, translates the implied {@link SingleTransactionRecord}.
     *
     * @param parts the parts of the transaction
     * @param spec the specification of the transaction record
     * @param remainingStateChanges the remaining state changes for this transactional unit
     * @param followingUnitTraces any traces following this transaction in its unit
     * @return the translated record
     */
    public SingleTransactionRecord recordFrom(
            @NonNull final BlockTransactionParts parts,
            @NonNull final Spec spec,
            @NonNull final List<StateChange> remainingStateChanges,
            @NonNull final List<TraceData> followingUnitTraces) {
        final var txnId = parts.transactionIdOrThrow();
        final var recordBuilder = TransactionRecord.newBuilder()
                .transactionHash(parts.transactionHash())
                .consensusTimestamp(parts.consensusTimestamp())
                .transactionID(txnId)
                .memo(parts.memo())
                .transactionFee(parts.transactionFee())
                .transferList(parts.transferList())
                .tokenTransferLists(parts.tokenTransferLists())
                .automaticTokenAssociations(parts.automaticTokenAssociations())
                .paidStakingRewards(parts.paidStakingRewards())
                .parentConsensusTimestamp(parts.parentConsensusTimestamp());
        final var receiptBuilder =
                TransactionReceipt.newBuilder().status(parts.transactionResult().status());
        final boolean followsUserRecord = asInstant(parts.consensusTimestamp()).isAfter(userTimestamp);
        if ((!followsUserRecord || parts.transactionIdOrThrow().scheduled())
                && parts.parentConsensusTimestamp() == null) {
            // Only preceding and user transactions get exchange rates in their receipts; note that
            // auto-account creations are always preceding dispatches and so get exchange rates
            receiptBuilder.exchangeRate(activeRates);
        }

        spec.accept(receiptBuilder, recordBuilder);
        if (!isContractOp(parts) && parts.hasContractOutput()) {
            final var output = parts.callContractOutputOrThrow();
            final var result =
                    resultBuilderFrom(output.evmTransactionResultOrThrow()).build();
            recordBuilder.contractCallResult(result);
        }
        // If this transaction was executed by virtue of being scheduled, set its schedule ref
        if (parts.transactionIdOrThrow().scheduled()) {
            Optional.ofNullable(scheduleRefs.get(parts.transactionIdOrThrow())).ifPresent(recordBuilder::scheduleRef);
        }
        final List<TransactionSidecarRecord> rebuiltSidecars;
        if (parts.hasTraces()) {
            rebuiltSidecars = recoveredSidecars(
                    parts.consensusTimestamp(),
                    parts.tracesOrThrow(),
                    followingUnitTraces,
                    remainingStateChanges,
                    parts);
        } else {
            rebuiltSidecars = emptyList();
        }
        return new SingleTransactionRecord(
                parts.transactionParts().wrapper(),
                recordBuilder.receipt(receiptBuilder.build()).build(),
                rebuiltSidecars,
                new SingleTransactionRecord.TransactionOutputs(null));
    }

    private List<TransactionSidecarRecord> recoveredSidecars(
            @NonNull final Timestamp now,
            @NonNull final List<TraceData> tracesHere,
            @NonNull final List<TraceData> followingUnitTraces,
            @NonNull final List<StateChange> remainingStateChanges,
            @NonNull final BlockTransactionParts parts) {
        final List<TransactionSidecarRecord> sidecars = new ArrayList<>();
        final var slotUpdates = remainingStateChanges.stream()
                .filter(change -> change.stateId() == StateIdentifier.STATE_ID_CONTRACT_STORAGE.protoOrdinal())
                .filter(StateChange::hasMapUpdate)
                .map(StateChange::mapUpdateOrThrow)
                .collect(toMap(
                        c -> c.keyOrThrow().slotKeyKeyOrThrow(),
                        c -> c.valueOrThrow().slotValueValueOrThrow().value()));
        final Map<SlotKey, Bytes> writtenSlots = new HashMap<>(slotUpdates);
        final var slotRemovals = remainingStateChanges.stream()
                .filter(change -> change.stateId() == StateIdentifier.STATE_ID_CONTRACT_STORAGE.protoOrdinal())
                .filter(StateChange::hasMapDelete)
                .map(StateChange::mapDeleteOrThrow)
                .collect(toMap(d -> d.keyOrThrow().slotKeyKeyOrThrow(), d -> Bytes.EMPTY));
        writtenSlots.putAll(slotRemovals);
        final var evmTraces = tracesHere.stream()
                .filter(TraceData::hasEvmTraceData)
                .map(TraceData::evmTraceDataOrThrow)
                .toList();
        final var followingEvmTraces = followingUnitTraces.stream()
                .filter(TraceData::hasEvmTraceData)
                .map(TraceData::evmTraceDataOrThrow)
                .toList();
        for (final var evmTraceData : evmTraces) {
            if (!evmTraceData.contractSlotUsages().isEmpty()) {
                final var slotUsages = evmTraceData.contractSlotUsages();
                final List<ContractStateChange> recoveredStateChanges = new ArrayList<>();
                for (final var slotUsage : slotUsages) {
                    final var contractId = slotUsage.contractIdOrThrow();
                    final List<StorageChange> recoveredChanges = new ArrayList<>();
                    final var writes = slotUsage.writtenSlotKeys();
                    slotUsage.slotReads().forEach(read -> {
                        final var builder = StorageChange.newBuilder().valueRead(read.readValue());
                        if (read.hasIndex()) {
                            final var writtenKey = writes.get(read.indexOrThrow());
                            final var slotKey = new SlotKey(contractId, ConversionUtils.leftPad32(writtenKey));
                            Bytes value = null;
                            for (final var nextEvmTraceData : followingEvmTraces) {
                                final var nextTracedWriteUsage = nextEvmTraceData.contractSlotUsages().stream()
                                        .filter(nextUsages ->
                                                nextUsages.contractIdOrThrow().equals(contractId)
                                                        && nextUsages.writtenSlotKeys().stream()
                                                                .anyMatch(nextWrite -> nextWrite.equals(writtenKey)))
                                        .findFirst();
                                if (nextTracedWriteUsage.isPresent()) {
                                    final int finalWriteIndex = nextTracedWriteUsage
                                            .get()
                                            .writtenSlotKeys()
                                            .indexOf(writtenKey);
                                    final var nextRead = nextTracedWriteUsage.get().slotReads().stream()
                                            .filter(r -> r.hasIndex() && r.indexOrThrow() == finalWriteIndex)
                                            .findFirst()
                                            .orElseThrow();
                                    value = nextRead.readValue();
                                    break;
                                }
                            }
                            if (value == null) {
                                final var valueFromState = writtenSlots.get(slotKey);
                                if (valueFromState == null) {
                                    throw new IllegalStateException("No written value found for write to " + slotKey
                                            + " in " + remainingStateChanges);
                                }
                                value = sansLeadingZeros(valueFromState);
                            }
                            builder.slot(writtenKey).valueWritten(value);
                        } else {
                            builder.slot(read.keyOrThrow());
                        }
                        recoveredChanges.add(builder.build());
                    });
                    recoveredStateChanges.add(new ContractStateChange(contractId, recoveredChanges));
                }
                sidecars.add(TransactionSidecarRecord.newBuilder()
                        .consensusTimestamp(now)
                        .stateChanges(new ContractStateChanges(recoveredStateChanges))
                        .build());
            }
            if (!evmTraceData.contractActions().isEmpty()) {
                final var actions = evmTraceData.contractActions();
                sidecars.add(TransactionSidecarRecord.newBuilder()
                        .consensusTimestamp(now)
                        .actions(new ContractActions(actions))
                        .build());
            }
            if (!evmTraceData.initcodes().isEmpty()) {
                for (final var initcode : evmTraceData.initcodes()) {
                    if (initcode.hasFailedInitcode()) {
                        sidecars.add(TransactionSidecarRecord.newBuilder()
                                .consensusTimestamp(now)
                                .bytecode(ContractBytecode.newBuilder()
                                        .initcode(initcode.failedInitcodeOrThrow())
                                        .build())
                                .build());
                    } else {
                        final var executedInitcode = initcode.executedInitcodeOrThrow();
                        final var contractId = executedInitcode.contractIdOrThrow();
                        final var bytecodeBuilder =
                                ContractBytecode.newBuilder().contractId(contractId);
                        final var bytecode = remainingStateChanges.stream()
                                .filter(StateChange::hasMapUpdate)
                                .filter(update -> update.stateId() == STATE_ID_CONTRACT_BYTECODE.protoOrdinal())
                                .filter(update -> update.mapUpdateOrThrow()
                                        .keyOrThrow()
                                        .contractIdKeyOrThrow()
                                        .equals(contractId))
                                .map(update ->
                                        update.mapUpdateOrThrow().valueOrThrow().bytecodeValueOrThrow())
                                .findAny();
                        // Runtime bytecode should always be recoverable from the state changes
                        if (bytecode.isEmpty()) {
                            throw new IllegalStateException("No bytecode state change found for contract " + contractId
                                    + " in " + remainingStateChanges + " (parts were " + parts + ")");
                        }
                        final var runtimeBytecode = bytecode.get().code();
                        bytecodeBuilder.runtimeBytecode(runtimeBytecode);
                        if (executedInitcode.hasExplicitInitcode()) {
                            bytecodeBuilder.initcode(executedInitcode.explicitInitcodeOrThrow());
                        } else if (executedInitcode.hasInitcodeBookends()) {
                            final var bookends = executedInitcode.initcodeBookendsOrThrow();
                            bytecodeBuilder.initcode(Bytes.merge(
                                    bookends.deployBytecode(),
                                    Bytes.merge(runtimeBytecode, bookends.metadataBytecode())));
                        }
                        sidecars.add(TransactionSidecarRecord.newBuilder()
                                .consensusTimestamp(now)
                                .bytecode(bytecodeBuilder)
                                .build());
                    }
                }
            }
        }
        return sidecars;
    }

    /**
     * Initializes a {@link ContractFunctionResult.Builder} from the given {@link EvmTransactionResult}.
     * @param result the EVM transaction result to initialize from
     * @return a builder for the contract function result
     */
    public static ContractFunctionResult.Builder resultBuilderFrom(@NonNull final EvmTransactionResult result) {
        requireNonNull(result);
        final var builder = ContractFunctionResult.newBuilder()
                .senderId(result.senderId())
                .contractID(result.contractId())
                .contractCallResult(result.resultData())
                .errorMessage(result.errorMessage())
                .gasUsed(result.gasUsed());
        if (result.hasInternalCallContext()) {
            final var context = result.internalCallContextOrThrow();
            builder.gas(context.gas()).functionParameters(context.callData()).amount(context.value());
        }
        return builder;
    }

    /**
     * Maps the given traces to verbose logs in the provided {@link ContractFunctionResult.Builder}.
     * @param resultBuilder the builder to populate with verbose logs
     * @param traces the list of traces to map to verbose logs
     */
    public static void mapTracesToVerboseLogs(
            @NonNull final ContractFunctionResult.Builder resultBuilder, @Nullable List<TraceData> traces) {
        if (traces == null || traces.stream().noneMatch(BaseTranslator::impliesLogs)) {
            resultBuilder.logInfo(List.of());
        } else {
            final List<Log> besuLogs = new ArrayList<>();
            final List<ContractLoginfo> verboseLogs = new ArrayList<>();
            traces.stream()
                    .filter(TraceData::hasEvmTraceData)
                    .map(TraceData::evmTraceDataOrThrow)
                    .forEach(traceData -> traceData.logs().forEach(log -> {
                        final var besuLog = asBesuLog(
                                log,
                                log.topics().stream()
                                        .map(ConversionUtils::leftPad32)
                                        .toList());
                        besuLogs.add(besuLog);
                        verboseLogs.add(asContractLogInfo(log, besuLog));
                    }));
            resultBuilder.logInfo(verboseLogs).bloom(bloomForAll(besuLogs));
        }
    }

    /**
     * Determines if the given {@link TraceData} implies that there are logs present in the V6 function result.
     * @param traceData the trace data to check
     * @return true if the trace data implies logs, false otherwise
     */
    private static boolean impliesLogs(@NonNull final TraceData traceData) {
        if (!traceData.hasEvmTraceData()) {
            return false;
        } else {
            final var evmTraceData = traceData.evmTraceDataOrThrow();
            return !evmTraceData.logs().isEmpty()
                    || !evmTraceData.contractSlotUsages().isEmpty()
                    || !evmTraceData.contractActions().isEmpty();
        }
    }

    /**
     * Converts a concise EVM transaction log into a verbose {@link ContractLoginfo}.
     *
     * @param log the concise EVM transaction log to convert
     * @param besuLog the Besu log associated with the EVM transaction log
     * @return the verbose {@link ContractLoginfo} representation of the log
     */
    private static ContractLoginfo asContractLogInfo(@NonNull final EvmTransactionLog log, @NonNull final Log besuLog) {
        requireNonNull(log);
        return ContractLoginfo.newBuilder()
                .contractID(log.contractIdOrThrow())
                .bloom(bloomFor(besuLog))
                .data(log.data())
                .topic(log.topics().stream().map(ConversionUtils::leftPad32).toList())
                .build();
    }

    private static Bytes sansLeadingZeros(@NonNull final Bytes bytes) {
        int i = 0;
        int n = (int) bytes.length();
        while (i < n && bytes.getByte(i) == 0) {
            i++;
        }
        if (i == n) {
            return Bytes.EMPTY;
        } else if (i == 0) {
            return bytes;
        } else {
            final var stripped = new byte[n - i];
            bytes.getBytes(i, stripped, 0, n - i);
            return Bytes.wrap(stripped);
        }
    }

    /**
     * Updates the active exchange rates with the contents of the given state change.
     * @param change the state change to update from
     */
    public void updateActiveRates(@NonNull final StateChange change) {
        final var contents =
                change.mapUpdateOrThrow().valueOrThrow().fileValueOrThrow().contents();
        try {
            activeRates = ExchangeRateSet.PROTOBUF.parse(contents);
        } catch (ParseException e) {
            throw new IllegalStateException("Rates file updated with unparseable contents", e);
        }
    }

    /**
     * Returns the active exchange rates.
     * @return the active exchange rates
     */
    public ExchangeRateSet activeRates() {
        return activeRates;
    }

    /**
     * Updates the nonces for accounts after processing the given transactional unit.
     * @param unit the transactional unit to process
     */
    public void updateNoncesAfter(@NonNull final BlockTransactionalUnit unit) {
        unit.stateChanges().forEach(stateChange -> {
            if (stateChange.hasMapUpdate()) {
                final var mapUpdate = stateChange.mapUpdateOrThrow();
                final var key = mapUpdate.keyOrThrow();
                if (key.hasAccountIdKey()) {
                    final var num = key.accountIdKeyOrThrow().accountNumOrThrow();
                    nonces.put(
                            num, mapUpdate.valueOrThrow().accountValueOrThrow().ethereumNonce());
                }
            }
        });
    }

    private void scanUnit(@NonNull final BlockTransactionalUnit unit) {
        final Map<TokenID, List<Long>> deletedSerialNos = new HashMap<>();
        unit.stateChanges().forEach(stateChange -> {
            if (stateChange.hasMapDelete()) {
                final var mapDelete = stateChange.mapDeleteOrThrow();
                final var key = mapDelete.keyOrThrow();
                if (key.hasScheduleIdKey()) {
                    purgedScheduleIds.add(key.scheduleIdKeyOrThrow());
                }
                // burn and wipe in batch can hide mints
                if (key.hasNftIdKey()) {
                    final var nftId = key.nftIdKeyOrThrow();
                    final var tokenId = nftId.tokenId();
                    deletedSerialNos
                            .computeIfAbsent(tokenId, ignore -> new LinkedList<>())
                            .add(nftId.serialNumber());
                }

            } else if (stateChange.hasMapUpdate()) {
                final var mapUpdate = stateChange.mapUpdateOrThrow();
                final var key = mapUpdate.keyOrThrow();
                if (key.hasTokenIdKey()) {
                    final var tokenId = mapUpdate.keyOrThrow().tokenIdKeyOrThrow();
                    if (!tokenTypes.containsKey(tokenId)) {
                        tokenTypes.put(
                                tokenId,
                                mapUpdate.valueOrThrow().tokenValueOrThrow().tokenType());
                    }
                    if (tokenId.tokenNum() > highestKnownEntityNum) {
                        nextCreatedNums
                                .computeIfAbsent(TOKEN, ignore -> new LinkedList<>())
                                .add(tokenId.tokenNum());
                    }
                } else if (key.hasTopicIdKey()) {
                    final var num = key.topicIdKeyOrThrow().topicNum();
                    if (num > highestKnownEntityNum) {
                        nextCreatedNums
                                .computeIfAbsent(TOPIC, ignore -> new LinkedList<>())
                                .add(num);
                    }
                } else if (key.hasFileIdKey()) {
                    final var num = key.fileIdKeyOrThrow().fileNum();
                    if (num > highestKnownEntityNum) {
                        nextCreatedNums
                                .computeIfAbsent(FILE, ignore -> new LinkedList<>())
                                .add(num);
                    }
                } else if (key.hasScheduleIdKey()) {
                    final var num = key.scheduleIdKeyOrThrow().scheduleNum();
                    if (num > highestKnownEntityNum) {
                        nextCreatedNums
                                .computeIfAbsent(SCHEDULE, ignore -> new LinkedList<>())
                                .add(num);
                    }
                    final var schedule = mapUpdate.valueOrThrow().scheduleValueOrThrow();
                    final var scheduleId = key.scheduleIdKeyOrThrow();
                    final var scheduledTxnId = scheduledTxnIdFrom(
                            schedule.originalCreateTransactionOrThrow().transactionIDOrThrow());
                    scheduleRefs.put(scheduledTxnId, scheduleId);
                    scheduleTxnIds.put(scheduleId, scheduledTxnId);
                } else if (key.hasAccountIdKey()) {
                    final var num = key.accountIdKeyOrThrow().accountNumOrThrow();
                    if (num > highestKnownEntityNum) {
                        nextCreatedNums
                                .computeIfAbsent(ACCOUNT, ignore -> new LinkedList<>())
                                .add(num);
                        final var account = mapUpdate.valueOrThrow().accountValueOrThrow();
                        evmAddresses.put(num, ConversionUtils.priorityAddressOf(account));
                    }
                } else if (key.hasEntityNumberKey()) {
                    final var value = mapUpdate.valueOrThrow();
                    if (value.hasNodeValue()) {
                        final long nodeId = key.entityNumberKeyOrThrow();
                        nextCreatedNums
                                .computeIfAbsent(NODE, ignore -> new LinkedList<>())
                                .add(nodeId);
                    }
                } else if (key.hasNftIdKey()) {
                    final var nftId = key.nftIdKeyOrThrow();
                    final var tokenId = nftId.tokenId();
                    highestPutSerialNos
                            .computeIfAbsent(tokenId, ignore -> new LinkedList<>())
                            .add(nftId.serialNumber());
                }
            }
        });
        userTimestamp = null;
        unit.blockTransactionParts().forEach(parts -> {
            if (parts.isTopLevel()) {
                userTimestamp = asInstant(parts.consensusTimestamp());
            }
            if (parts.functionality() == HederaFunctionality.TOKEN_MINT) {
                if (parts.status() == SUCCESS) {
                    final var op = parts.body().tokenMintOrThrow();
                    final var numMetadata = op.metadata().size();
                    if (numMetadata > 0) {
                        final var tokenId = op.tokenOrThrow();
                        numMints.merge(tokenId, numMetadata, Integer::sum);
                    }
                }
            }
        });
        // in batch deleted serials will overwrite minted state changes
        // and those serials will be missed in highestPutSerialNos
        maybeDeletedSerialsInBatch(unit, deletedSerialNos);
    }

    private static boolean isContractOp(@NonNull final BlockTransactionParts parts) {
        final var function = parts.functionality();
        return function == CONTRACT_CALL || function == CONTRACT_CREATE || function == ETHEREUM_TRANSACTION;
    }

    private static Account findContractOrThrow(
            @NonNull final ContractID contractId, @NonNull final List<StateChange> stateChanges) {
        return stateChanges.stream()
                .filter(change -> change.stateId() == STATE_ID_ACCOUNTS.protoOrdinal())
                .filter(StateChange::hasMapUpdate)
                .map(StateChange::mapUpdateOrThrow)
                .filter(change -> change.keyOrThrow().hasAccountIdKey())
                .filter(change -> change.valueOrThrow().accountValueOrThrow().smartContract())
                .map(change -> change.valueOrThrow().accountValueOrThrow())
                .filter(contract -> {
                    final var accountId = contract.accountIdOrThrow();
                    return contractId.shardNum() == accountId.shardNum()
                            && contractId.realmNum() == accountId.realmNum()
                            && contractId.contractNumOrThrow().longValue() == accountId.accountNumOrThrow();
                })
                .findFirst()
                .orElseThrow();
    }

    private static Optional<Account> findAccount(
            @NonNull final AccountID accountId, @NonNull final List<StateChange> stateChanges) {
        return stateChanges.stream()
                .filter(change -> change.stateId() == STATE_ID_ACCOUNTS.protoOrdinal())
                .filter(StateChange::hasMapUpdate)
                .map(StateChange::mapUpdateOrThrow)
                .filter(change -> change.keyOrThrow().hasAccountIdKey())
                .filter(change -> !change.valueOrThrow().accountValueOrThrow().smartContract())
                .map(change -> change.valueOrThrow().accountValueOrThrow())
                .filter(account -> account.accountIdOrThrow().equals(accountId))
                .findFirst();
    }

    /**
     * This method tries to identify missing mapUpdate state changes with NftID, in case of mixed mint, burn, and wipe
     * transactions in atomic batch. If such, it will use mapDelete changes to fill missing ones.
     *
     * @param unit The block transactional unit.
     * @param deletedMintSerialNos Map derived from all mapDelete state changes with NftID key in the given unit.
     */
    private void maybeDeletedSerialsInBatch(
            BlockTransactionalUnit unit, Map<TokenID, List<Long>> deletedMintSerialNos) {
        // if this unit is an atomic batch and not all mints are found in mapUpdate state changes,
        // try to identify the missing ones in mapDelete state changes
        if (isBatch(unit) && !allMintsAreFound()) {
            final Map<TokenID, List<Long>> possibleMintSerialNos = new HashMap<>();
            deletedMintSerialNos.forEach((tokenID, serials) -> {
                if (numMints.containsKey(tokenID)) {
                    possibleMintSerialNos.put(tokenID, serials);
                }
            });

            // if possible minted serials found, merge them in highestPutSerialNos
            if (!possibleMintSerialNos.isEmpty()) {
                possibleMintSerialNos.forEach((token, serials) -> {
                    // add missing token serials
                    highestPutSerialNos.computeIfAbsent(token, ignore -> serials);
                    // merge serials for present tokens
                    highestPutSerialNos.computeIfPresent(token, (key, list) -> {
                        Set<Long> mergedSet = new HashSet<>(list);
                        mergedSet.addAll(serials);
                        return new ArrayList<>(mergedSet);
                    });
                });
            }
        }
    }

    private boolean isBatch(BlockTransactionalUnit unit) {
        return unit.blockTransactionParts().stream().anyMatch(part -> part.functionality() == ATOMIC_BATCH);
    }

    private boolean allMintsAreFound() {
        // compare number of token mints
        if (numMints.size() != highestPutSerialNos.size()) {
            return false;
        }
        // compare number of serials
        for (Map.Entry<TokenID, Integer> entry : numMints.entrySet()) {
            TokenID token = entry.getKey();
            Integer count = entry.getValue();
            final var serials = highestPutSerialNos.get(token);
            if (serials != null && serials.size() != count) {
                return false;
            }
        }
        return true;
    }
}
