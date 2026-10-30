// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.records;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord.nonStakingRecordsFrom;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.triggerAndCloseAtLeastOneFileIfNotInterrupted;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsAssertion.SkipSynthItems.NO;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.NodeStakeUpdate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.utilops.streams.assertions.EventualStreamAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItems;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsAssertion;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** Verifies that mirrors can identify exactly the contract deletions committed by EVM execution. */
@Tag(SMART_CONTRACT)
@HapiTestLifecycle
@DisplayName("Committed SELFDESTRUCT records")
public class SelfDestructRecordsSuite {
    private static final String FACTORY = "SelfDestructLifecycle";
    private static final String CONSTRUCTOR = "FactorySelfDestructConstructor";
    private static final String CALLABLE = "SelfDestructCallable";
    private static final String BENEFICIARY = "beneficiary";
    private static final String TXN = "selfDestruct";
    private static final String FINISHED = "finished";
    private static final String EXTERNALIZATION_ENABLED = "contracts.selfDestructs.externalization.enabled";
    private static final String MAX_FOLLOWING_RECORDS = "consensus.handle.maxFollowingRecords";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(uploadInitCode(FACTORY, CONSTRUCTOR, CALLABLE, "Fuse"));
    }

    @HapiTest
    @DisplayName("identify a top-level constructor deletion after its surviving child creation")
    final Stream<DynamicTest> constructorDeletionIsExternalized() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 1, BENEFICIARY, CONSTRUCTOR, SUCCESS),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(CONSTRUCTOR)
                        .balance(ONE_HBAR)
                        .payingWith(BENEFICIARY)
                        .via(TXN)
                        .refusingEthConversion(),
                getContractInfo(CONSTRUCTOR).has(contractWith().isDeleted()),
                finish());
    }

    @HapiTest
    @DisplayName("identify a CREATE child deleted in the same transaction")
    final Stream<DynamicTest> createdChildDeletionIsExternalized() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 1, BENEFICIARY, null, SUCCESS),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(FACTORY),
                // Create a funded child, then SELFDESTRUCT it to the transaction payer.
                contractCall(FACTORY, "destroyChildren", BigInteger.ONE, false, false)
                        .sending(ONE_HBAR)
                        .gas(1_000_000)
                        .payingWith(BENEFICIARY)
                        .via(TXN),
                createdChildrenAreDeleted(TXN, 1),
                finish());
    }

    @HapiTest
    @DisplayName("emit one ordered deletion per contract despite repeated SELFDESTRUCT")
    final Stream<DynamicTest> repeatedDestructionsAreDeduplicated() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 2, BENEFICIARY, null, SUCCESS),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(FACTORY),
                // Create two children and invoke SELFDESTRUCT twice on each before finalization.
                contractCall(FACTORY, "destroyChildren", BigInteger.TWO, true, false)
                        .sending(ONE_HBAR)
                        .gas(2_000_000)
                        .payingWith(BENEFICIARY)
                        .via(TXN),
                createdChildrenAreDeleted(TXN, 2),
                finish());
    }

    @HapiTest
    @DisplayName("omit a deletion when an outer frame reverts")
    final Stream<DynamicTest> outerRevertDoesNotExternalizeDeletion() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 0, BENEFICIARY, null, CONTRACT_REVERT_EXECUTED),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(FACTORY),
                // Revert after creating and destroying a child so neither state change is committed.
                contractCall(FACTORY, "destroyChildren", BigInteger.ONE, false, true)
                        .gas(1_000_000)
                        .payingWith(BENEFICIARY)
                        .via(TXN)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                finish());
    }

    @HapiTest
    @DisplayName("omit a deletion from a reverted child frame caught by a successful parent")
    final Stream<DynamicTest> caughtRevertDoesNotExternalizeDeletion() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 0, BENEFICIARY, null, SUCCESS),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(FACTORY),
                // Catch the reverting subcall after its child has executed SELFDESTRUCT.
                contractCall(FACTORY, "catchRevertedDestruction")
                        .gas(1_000_000)
                        .payingWith(BENEFICIARY)
                        .via(TXN),
                finish());
    }

    @HapiTest
    @DisplayName("retain the committed beneficiary when a later SELFDESTRUCT attempt reverts")
    final Stream<DynamicTest> revertedAttemptDoesNotReplaceBeneficiary() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 1, BENEFICIARY, null, SUCCESS),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(FACTORY),
                // Destroy to the payer, then catch a reverted attempt to destroy to the factory instead.
                contractCall(FACTORY, "preserveCommittedBeneficiary")
                        .gas(1_000_000)
                        .payingWith(BENEFICIARY)
                        .via(TXN),
                createdChildrenAreDeleted(TXN, 1),
                finish());
    }

    @HapiTest
    @DisplayName("omit a deletion for an EIP-6780 balance-only sweep")
    final Stream<DynamicTest> preExistingContractIsNotReportedDeleted() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 0, BENEFICIARY, null, SUCCESS),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(CALLABLE).balance(ONE_HBAR),
                // A pre-existing contract transfers its balance but survives SELFDESTRUCT under EIP-6780.
                contractCall(CALLABLE, "destroy").payingWith(BENEFICIARY).via(TXN),
                getContractInfo(CALLABLE).has(contractWith().isNotDeleted().balance(0L)),
                finish());
    }

    @HapiTest
    @Tag(ATOMIC_BATCH)
    @DisplayName("include a deletion committed by an atomic batch")
    final Stream<DynamicTest> successfulAtomicBatchExternalizesDeletion() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 1, BENEFICIARY, null, SUCCESS),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(FACTORY),
                // Commit the child's creation and destruction as an inner batch transaction.
                atomicBatch(contractCall(FACTORY, "destroyChildren", BigInteger.ONE, false, false)
                                .gas(1_000_000)
                                .payingWith(BENEFICIARY)
                                .batchKey(BENEFICIARY)
                                .via(TXN))
                        .payingWith(BENEFICIARY),
                createdChildrenAreDeleted(TXN, 1),
                finish());
    }

    @HapiTest
    @Tag(ATOMIC_BATCH)
    @DisplayName("omit deletions when the final EVM operation rolls back an atomic batch")
    final Stream<DynamicTest> revertingEvmOperationRollsBackAtomicBatch() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 0, BENEFICIARY, null, CONTRACT_REVERT_EXECUTED),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(FACTORY),
                // Batches permit one EVM operation in final position; its revert also rolls back the earlier transfer.
                atomicBatch(
                                cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FACTORY, ONE_HBAR))
                                        .batchKey(BENEFICIARY)
                                        .hasKnownStatus(REVERTED_SUCCESS),
                                contractCall(FACTORY, "destroyChildren", BigInteger.ONE, false, true)
                                        .gas(1_000_000)
                                        .payingWith(BENEFICIARY)
                                        .batchKey(BENEFICIARY)
                                        .via(TXN)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .payingWith(BENEFICIARY)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getContractInfo(FACTORY).has(contractWith().isNotDeleted().balance(0L)),
                finish());
    }

    @LeakyHapiTest(overrides = {"consensus.handle.maxFollowingRecords"})
    @DisplayName("roll back EVM state when creation fits but its deletion record exceeds capacity")
    final Stream<DynamicTest> deletionRecordCapacityFailureRollsBackExecution() {
        return hapiTest(
                deletionRecords(TXN, FINISHED, 0, BENEFICIARY, null, MAX_CHILD_RECORDS_EXCEEDED),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(FACTORY),
                overriding("consensus.handle.maxFollowingRecords", "1"),
                // CREATE fits; SELFDESTRUCT transfers HBAR and writes storage before finalization cannot record its
                // deletion.
                sourcingContextual(spec -> contractCall(
                                FACTORY,
                                "destroyChildTo",
                                idAsHeadlongAddress(spec.registry().getAccountID(BENEFICIARY)))
                        .sending(ONE_HBAR)
                        .gas(1_000_000)
                        .via(TXN)
                        .refusingEthConversion()
                        .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED)),
                blockingOrder(
                        getTxnRecord(TXN).andAllChildRecords().hasNonStakingChildRecordCount(0),
                        getAccountBalance(BENEFICIARY).hasTinyBars(ONE_HUNDRED_HBARS),
                        getContractInfo(FACTORY)
                                .has(contractWith().isNotDeleted().balance(0L)),
                        contractCallLocal(FACTORY, "completedDestructions")
                                .exposingTypedResultsTo(results -> assertEquals(BigInteger.ZERO, results[0]))),
                finish());
    }

    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    @DisplayName("record several pre-existing contract deletions in unsigned EVM-address order before EIP-6780")
    final Stream<DynamicTest> preCancunDeletesSeveralPreExistingContracts() {
        final var creation = "fuseCreation";
        return hapiTest(
                preExistingDeletionRecords(creation, TXN, FINISHED, 3, "Fuse"),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                overriding("contracts.evm.version", "v0.46"),
                // Fuse's constructor creates three persistent Dynamite contracts, all targeting Fuse as beneficiary.
                contractCreate("Fuse").gas(1_000_000).via(creation).refusingEthConversion(),
                // A later transaction SELFDESTRUCTs all three pre-existing children.
                contractCall("Fuse", "light")
                        .gas(1_000_000)
                        .payingWith(BENEFICIARY)
                        .via(TXN),
                blockingOrder(
                        getTxnRecord(TXN).hasNonStakingChildRecordCount(3),
                        createdChildrenAreDeleted(creation, 3),
                        getContractInfo("Fuse").has(contractWith().isNotDeleted())),
                finish());
    }

    @LeakyHapiTest(overrides = {MAX_FOLLOWING_RECORDS, EXTERNALIZATION_ENABLED})
    @DisplayName("fit 25 create-and-delete pairs in 50 child slots and restore the old limit when disabled")
    final Stream<DynamicTest> externalizationUsesTwoChildSlotsPerPair() {
        final var atBoundary = "twentyFivePairs";
        final var pastBoundary = "twentySixPairs";
        final var disabled = "disabledExternalization";
        return hapiTest(
                deletionRecords(atBoundary, FINISHED, 25, BENEFICIARY, null, SUCCESS),
                deletionRecords(pastBoundary, FINISHED, 0, BENEFICIARY, null, MAX_CHILD_RECORDS_EXCEEDED),
                deletionRecords(disabled, FINISHED, 0, BENEFICIARY, null, SUCCESS),
                startNewRecordFile(),
                cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                contractCreate(FACTORY),
                overridingTwo(MAX_FOLLOWING_RECORDS, "50", EXTERNALIZATION_ENABLED, "true"),
                // Each committed pair needs one creation record and one deletion record: 25 pairs exactly fill 50
                // slots.
                contractCall(FACTORY, "destroyChildren", BigInteger.valueOf(25), false, false)
                        .sending(25)
                        .gas(10_000_000)
                        .payingWith(BENEFICIARY)
                        .via(atBoundary),
                blockingOrder(
                        getTxnRecord(atBoundary).hasNonStakingChildRecordCount(50),
                        createdChildrenAreDeleted(atBoundary, 25)),
                // Execution reaches all 26 SELFDESTRUCTs, but finalization must roll back the entire over-capacity
                // call.
                contractCall(FACTORY, "destroyChildren", BigInteger.valueOf(26), false, false)
                        .sending(26)
                        .gas(10_000_000)
                        .payingWith(BENEFICIARY)
                        .via(pastBoundary)
                        .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED),
                blockingOrder(
                        getTxnRecord(pastBoundary).hasNonStakingChildRecordCount(0),
                        getContractInfo(FACTORY)
                                .has(contractWith().isNotDeleted().balance(0L)),
                        contractCallLocal(FACTORY, "completedDestructions")
                                .exposingTypedResultsTo(results -> assertEquals(BigInteger.valueOf(25), results[0]))),
                overriding(EXTERNALIZATION_ENABLED, "false"),
                // With externalization disabled, the same 26 pairs commit using only their 26 creation records.
                contractCall(FACTORY, "destroyChildren", BigInteger.valueOf(26), false, false)
                        .sending(26)
                        .gas(10_000_000)
                        .payingWith(BENEFICIARY)
                        .via(disabled),
                blockingOrder(
                        getTxnRecord(disabled).hasNonStakingChildRecordCount(26),
                        createdChildrenAreDeleted(disabled, 26),
                        contractCallLocal(FACTORY, "completedDestructions")
                                .exposingTypedResultsTo(results -> assertEquals(BigInteger.valueOf(51), results[0]))),
                finish());
    }

    /** Closes the file already open at subscription, since record observers only receive newly created files. */
    public static HapiSpecOperation startNewRecordFile() {
        return withOpContext((spec, log) -> triggerAndCloseAtLeastOneFileIfNotInterrupted(spec));
    }

    /** A later transaction bounds negative assertions and detects extra deletion records. */
    private static HapiSpecOperation finish() {
        return cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, BENEFICIARY, 1)).via(FINISHED);
    }

    /** Confirms that the child IDs observed through HAPI are actually deleted in consensus state. */
    private static HapiSpecOperation createdChildrenAreDeleted(final String txn, final int count) {
        return withOpContext((spec, log) -> {
            final var query = getTxnRecord(txn).andAllChildRecords();
            allRunFor(spec, query);
            final var creations = nonStakingRecordsFrom(query.getChildRecords()).stream()
                    .filter(record -> record.hasContractCreateResult())
                    .toList();
            assertEquals(count, creations.size());
            for (final var creation : creations) {
                final var id = creation.getReceipt().getContractID();
                allRunFor(
                        spec,
                        getContractInfo(id.getShardNum() + "." + id.getRealmNum() + "." + id.getContractNum())
                                .has(contractWith().isDeleted()));
            }
        });
    }

    /**
     * Validates the serialized synthetic bodies and records only after a later marker transaction has arrived.
     * A null target means every successfully created child should have exactly one deletion.
     */
    public static EventualStreamAssertion deletionRecords(
            @NonNull final String txn,
            @NonNull final String finishedTxn,
            final int count,
            @NonNull final String beneficiary,
            @Nullable final String target,
            @NonNull final ResponseCodeEnum parentStatus) {
        return streamMustIncludePassFrom(spec -> new VisibleItemsAssertion(
                spec,
                (s, items) -> assertDeletions(s, items.get(txn), count, beneficiary, target, parentStatus),
                NO,
                txn,
                finishedTxn));
    }

    /** Compares deletion records with the child contracts created by a prior transaction. */
    private static EventualStreamAssertion preExistingDeletionRecords(
            final String creationTxn,
            final String txn,
            final String finishedTxn,
            final int count,
            final String beneficiary) {
        return streamMustIncludePassFrom(spec -> new VisibleItemsAssertion(
                spec,
                (s, items) ->
                        assertDeletions(s, items.get(txn), count, beneficiary, null, SUCCESS, items.get(creationTxn)),
                NO,
                creationTxn,
                txn,
                finishedTxn));
    }

    /** Reads the address from the actual creation result, falling back to the canonical address for unaliased creates. */
    private static byte[] creationAddress(@NonNull final RecordStreamEntry creation) {
        final var address =
                creation.txnRecord().getContractCreateResult().getEvmAddress().getValue();
        return address.isEmpty()
                ? asSolidityAddress(creation.txnRecord().getReceipt().getContractID())
                : address.toByteArray();
    }

    private static void assertDeletions(
            @NonNull final HapiSpec spec,
            @NonNull final VisibleItems items,
            final int count,
            @NonNull final String beneficiary,
            @Nullable final String target,
            @NonNull final ResponseCodeEnum parentStatus) {
        assertDeletions(spec, items, count, beneficiary, target, parentStatus, null);
    }

    private static void assertDeletions(
            @NonNull final HapiSpec spec,
            @NonNull final VisibleItems items,
            final int count,
            @NonNull final String beneficiary,
            @Nullable final String target,
            @NonNull final ResponseCodeEnum parentStatus,
            @Nullable final VisibleItems priorCreationItems) {
        final var entries = items.entries().stream()
                .filter(e -> e.function() != NodeStakeUpdate)
                .toList();
        final var parent = entries.getFirst();
        assertEquals(parentStatus, parent.finalStatus());
        final var deletions =
                entries.stream().filter(e -> e.function() == ContractDelete).toList();
        assertEquals(count, deletions.size(), "Only committed deletions must be externalized");
        if (count == 0) {
            return;
        }
        final var creationEntries = priorCreationItems == null ? entries : priorCreationItems.entries();
        final List<ContractID> expectedIds = target == null
                ? creationEntries.stream()
                        .filter(e -> e.function() == ContractCreate)
                        .filter(e -> priorCreationItems == null
                                || e.txnRecord().getTransactionID().getNonce() > 0)
                        .sorted((left, right) -> Arrays.compareUnsigned(creationAddress(left), creationAddress(right)))
                        .map(e -> e.txnRecord().getReceipt().getContractID())
                        .toList()
                : List.of(spec.registry().getContractId(target));
        final var actualIds = deletions.stream()
                .map(e -> e.body().getContractDeleteInstance().getContractID())
                .toList();
        assertEquals(expectedIds, actualIds, "Delete contracts in unsigned ascending EVM-address order");
        assertEquals(count, new HashSet<>(actualIds).size(), "Do not duplicate a deletion");
        var previous = entries.stream()
                .filter(e -> e.function() != ContractDelete)
                .map(RecordStreamEntry::consensusTime)
                .max(java.util.Comparator.naturalOrder())
                .orElseThrow();
        for (final var deletion : deletions) {
            final var body = deletion.body().getContractDeleteInstance();
            final var record = deletion.txnRecord();
            assertEquals(SUCCESS, deletion.finalStatus());
            assertTrue(body.getContractID().getContractNum() > 0, "Use a canonical numeric contract ID");
            assertEquals(body.getContractID(), record.getReceipt().getContractID());
            assertEquals(spec.registry().getAccountID(beneficiary), body.getTransferAccountID());
            assertFalse(body.hasTransferContractID());
            assertEquals(0, record.getTransactionFee(), "EVM gas already paid for SELFDESTRUCT");
            assertEquals(
                    0, record.getTransferList().getAccountAmountsCount(), "Do not repeat the EVM balance transfer");
            assertEquals(0, record.getTokenTransferListsCount());
            assertFalse(record.hasContractCallResult());
            assertFalse(record.hasContractCreateResult());
            assertEquals(parent.txnRecord().getConsensusTimestamp(), record.getParentConsensusTimestamp());
            assertTrue(record.getTransactionID().getNonce()
                    > parent.txnRecord().getTransactionID().getNonce());
            assertTrue(deletion.consensusTime().isAfter(previous), "Deletion records follow creation records");
            previous = deletion.consensusTime();
        }
    }
}
