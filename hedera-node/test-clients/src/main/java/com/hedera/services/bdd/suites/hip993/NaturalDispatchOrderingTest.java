// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip993;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.operations.transactions.TouchBalancesOperation.touchBalanceOf;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.triggerAndCloseAtLeastOneFile;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludeNoFailuresFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.visibleNonSyntheticItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.hip904.UnlimitedAutoAssociationSuite.UNLIMITED_AUTO_ASSOCIATION_SLOTS;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCall;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleCreate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.IDENTICAL_SCHEDULE_ALREADY_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.StreamDataListener;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItems;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Asserts the expected presence and order of all valid combinations of preceding and following stream items;
 * both when rolled back and directly committed. It particularly emphasizes the natural ordering of
 * {@link TransactionCategory#PRECEDING} stream items as defined in
 * HIP-993 <a href="https://hips.hedera.com/hip/hip-993#natural-ordering-of-preceding-records">here</a>.
 * <p>
 * The only stream items created in a savepoint that are <b>not</b> expected to be present are those with reversing
 * behavior {@link StreamBuilder.ReversingBehavior#REMOVABLE}, and whose originating savepoint was rolled back.
 * <p>
 * All other stream items are expected to be present in the record stream once created; but if they are
 * {@link StreamBuilder.ReversingBehavior#REVERSIBLE}, their status may be changed from {@code SUCCESS} to
 * {@code REVERTED_SUCCESS} when their originating savepoint is rolled back.
 * <p>
 * Only {@link StreamBuilder.ReversingBehavior#IRREVERSIBLE} streams items appear unchanged in the record stream no matter whether
 * their originating savepoint is rolled back.
 */
@DisplayName("given HIP-993 natural dispatch ordering")
@HapiTestLifecycle
public class NaturalDispatchOrderingTest {
    private static Runnable unsubscribe;

    // Ensure the record stream monitor is watching the streams directory to stabilize in CI
    // (FUTURE) Make record stream access more resilient in CI so this isn't needed
    @BeforeAll
    static void setUp(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(withOpContext((spec, opLog) -> {
            unsubscribe = STREAM_FILE_ACCESS.subscribe(
                    guaranteedExtantDir(spec.recordStreamsLoc(byNodeId(0))), new StreamDataListener() {});
            triggerAndCloseAtLeastOneFile(spec);
        }));
    }

    @AfterAll
    static void cleanUp() {
        unsubscribe.run();
    }

    /**
     * Tests the {@link TransactionCategory#USER} + {@link ReversingBehavior#REVERSIBLE} combination for
     * both commit and rollback via,
     * <ol>
     *     <Li>Successfully creating a scheduled transaction.</Li>
     *     <Li>Failing to create an identical schedule transaction, which leaves some information in the receipt
     *     despite rolling back the root savepoint stack.</Li>
     * </ol>
     *
     * @return the test
     */
    @HapiTest
    @DisplayName("reversible user stream items are as expected")
    final Stream<DynamicTest> reversibleUserItemsAsExpected() {
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(
                        visibleNonSyntheticItems(reversibleUserValidator(), "firstCreation", "duplicateCreation")),
                scheduleCreate(
                                "scheduledTxn",
                                cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1))
                                        .fee(ONE_HBAR))
                        .via("firstCreation"),
                scheduleCreate(
                                "duplicateScheduledTxn",
                                cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1))
                                        .fee(ONE_HBAR))
                        .via("duplicateCreation")
                        .hasKnownStatus(IDENTICAL_SCHEDULE_ALREADY_CREATED));
    }

    /**
     * Tests the {@link TransactionCategory#CHILD} + {@link ReversingBehavior#REVERSIBLE} combination as
     * well as the {@link TransactionCategory#PRECEDING} + {@link ReversingBehavior#REMOVABLE} combination for
     * both commit and rollback via,
     * <ol>
     *     <Li>Successfully calling a transfer system contract that does an auto-association in a frame that doesn't
     *     revert, within an EVM transaction that doesn't revert.</Li>
     *     <Li>Calling a transfer system contract that does an auto-association in a frame that reverts, within an
     *     EVM transaction that doesn't revert.</Li>
     *     <Li>Calling a transfer system contract that does an auto-association in a frame that succeeds, but within
     *     an EVM transaction that reverts.</Li>
     * </ol>
     *
     * @return the test
     */
    @HapiTest
    @DisplayName("reversible child and removable preceding stream items are as expected")
    @Tag(MATS)
    final Stream<DynamicTest> reversibleChildAndRemovablePrecedingItemsAsExpected(
            @NonFungibleToken(numPreMints = 2) SpecNonFungibleToken nonFungibleToken,
            @Account(maxAutoAssociations = 1) SpecAccount beneficiary,
            @Contract(contract = "PrecompileAliasXfer", creationGas = 5_000_000) SpecContract transferContract,
            @Contract(contract = "LowLevelCall", creationGas = 3_000_000) SpecContract lowLevelCallContract) {
        final var transferFunction = new Function("transferNFTThanRevertCall(address,address,address,int64)");
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(visibleNonSyntheticItems(
                        reversibleChildValidator(), "fullSuccess", "containedRevert", "fullRevert")),
                nonFungibleToken.treasury().authorizeContract(transferContract),
                transferContract
                        .call("transferNFTCall", nonFungibleToken, nonFungibleToken.treasury(), beneficiary, 1L)
                        .andAssert(txn -> txn.gas(2_000_000).via("fullSuccess")),
                withOpContext((spec, opLog) -> {
                    final var calldata = transferFunction.encodeCallWithArgs(
                            nonFungibleToken.addressOn(spec.targetNetworkOrThrow()),
                            nonFungibleToken.treasury().addressOn(spec.targetNetworkOrThrow()),
                            beneficiary.addressOn(spec.targetNetworkOrThrow()),
                            2L);
                    allRunFor(
                            spec,
                            lowLevelCallContract
                                    .call(
                                            "callRequestedAndIgnoreFailure",
                                            transferContract,
                                            calldata.array(),
                                            BigInteger.valueOf(1_000_000))
                                    .andAssert(txn -> txn.gas(2_000_000).via("containedRevert")));
                }),
                transferContract
                        .call(
                                "transferNFTThanRevertCall",
                                nonFungibleToken,
                                nonFungibleToken.treasury(),
                                beneficiary,
                                2L)
                        .andAssert(txn -> txn.via("fullRevert").hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                nonFungibleToken.serialNo(2L).assertOwnerIs(nonFungibleToken.treasury()));
    }

    /**
     * Tests the {@link TransactionCategory#SCHEDULED} + {@link ReversingBehavior#REVERSIBLE} combination as
     * well as the {@link TransactionCategory#PRECEDING} + {@link ReversingBehavior#REMOVABLE} combination for
     * both commit and rollback via,
     * <ol>
     *     <Li>Successfully creating an immediately triggered transfer that does two auto-associations with
     *     a payer that can afford all the fees.</Li>
     *     <Li>Successfully creating an immediately triggered transfer that does two auto-associations with
     *     a payer that cannot afford all the fees, and hence rolls back its first preceding child due to
     *     a {@link ResponseCodeEnum#INSUFFICIENT_PAYER_BALANCE} result on the second auto-association.</Li>
     * </ol>
     *
     * @return the test
     */
    @HapiTest
    @DisplayName("reversible schedule stream items and removable preceding stream items are as expected")
    final Stream<DynamicTest> reversibleScheduleAndRemovablePrecedingItemsAsExpected(
            @FungibleToken SpecFungibleToken firstToken,
            @FungibleToken SpecFungibleToken secondToken,
            @Account(maxAutoAssociations = 2) SpecAccount firstReceiver,
            @Account(maxAutoAssociations = 2) SpecAccount secondReceiver,
            @Account(centBalance = 100, maxAutoAssociations = UNLIMITED_AUTO_ASSOCIATION_SLOTS)
                    SpecAccount solventPayer,
            @Account(centBalance = 7, maxAutoAssociations = UNLIMITED_AUTO_ASSOCIATION_SLOTS)
                    SpecAccount insolventPayer) {
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(
                        visibleNonSyntheticItems(reversibleScheduleValidator(), "committed", "rolledBack")),
                firstToken.treasury().transferUnitsTo(solventPayer, 10, firstToken),
                secondToken.treasury().transferUnitsTo(insolventPayer, 10, secondToken),
                // Ensure the receiver entities exist before switching out object-oriented DSL
                touchBalanceOf(firstReceiver, secondReceiver),
                // Immediate trigger a schedule dispatch that succeeds as payer can afford two auto-associations
                scheduleCreate(
                                "committedTxn",
                                cryptoTransfer(moving(2, firstToken.name())
                                                .distributing(
                                                        solventPayer.name(),
                                                        firstReceiver.name(),
                                                        secondReceiver.name()))
                                        .fee(ONE_HBAR / 10))
                        .designatingPayer(solventPayer.name())
                        .alsoSigningWith(solventPayer.name())
                        .via("committed"),
                // Immediate trigger a schedule dispatch that rolls back as payer cannot afford two auto-associations
                scheduleCreate(
                                "rolledBackTxn",
                                cryptoTransfer(moving(2, secondToken.name())
                                                .distributing(
                                                        insolventPayer.name(),
                                                        firstReceiver.name(),
                                                        secondReceiver.name()))
                                        .fee(ONE_HBAR / 10))
                        .designatingPayer(insolventPayer.name())
                        .alsoSigningWith(insolventPayer.name())
                        .via("rolledBack"));
    }

    /**
     * Tests the {@link TransactionCategory#CHILD} + {@link ReversingBehavior#REMOVABLE} combination as
     * both commit and rollback via,
     * <ol>
     *     <Li>Triggering a sequence of creations in an EVM transaction that does not revert.</Li>
     *     <Li>Triggering a sequence of creations in an EVM transaction that does revert.</Li>
     * </ol>
     *
     * @return the test
     */
    @HapiTest
    @DisplayName("removable child stream items are as expected")
    @Tag(MATS)
    final Stream<DynamicTest> removableChildItemsAsExpected(
            @Contract(contract = "OuterCreator", creationGas = 2_000_000) SpecContract outerCreatorContract,
            @Contract(contract = "LowLevelCall", creationGas = 2_000_000) SpecContract lowLevelCallContract) {
        final var startChainFn = new Function("startChain(bytes)");
        final var emptyMessage = new byte[0];
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(
                        visibleNonSyntheticItems(removableChildValidator(), "nestedCreations", "revertedCreations")),
                outerCreatorContract.call("startChain", emptyMessage).with(txn -> txn.gas(5_000_000)
                        .via("nestedCreations")),
                withOpContext((spec, opLog) -> {
                    final var calldata = startChainFn.encodeCallWithArgs(emptyMessage);
                    allRunFor(
                            spec,
                            lowLevelCallContract
                                    .call(
                                            "callRequestedAndRevertAfterIgnoringFailure",
                                            outerCreatorContract.addressOn(spec.targetNetworkOrThrow()),
                                            calldata.array(),
                                            BigInteger.valueOf(2_000_000))
                                    .with(txn -> txn.gas(4_000_000)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .via("revertedCreations")));
                }));
    }

    /**
     * Tests the {@link TransactionCategory#PRECEDING} + {@link ReversingBehavior#IRREVERSIBLE} combination as
     * both commit and rollback via,
     * <ol>
     *     <Li>Triggering a hollow account completion with a simple transfer that succeeds.</Li>
     *     <Li>Triggering a hollow account completion with a simple transfer that fails.</Li>
     * </ol>
     *
     * @return the test
     */
    @HapiTest
    @DisplayName("irreversible preceding stream items are as expected")
    final Stream<DynamicTest> irreversiblePrecedingItemsAsExpected() {
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(visibleNonSyntheticItems(
                        irreversiblePrecedingValidator(), "finalizationBySuccess", "finalizationByFailure")),
                tokenCreate("unassociatedToken"),
                // Create two hollow accounts to finalize, first by a top-level success and second by failure
                createHollow(2, i -> "hollow" + i),
                cryptoTransfer(tinyBarsFromTo("hollow0", FUNDING, 1))
                        .fee(ONE_HBAR)
                        .payingWith("hollow0")
                        .signedBy("hollow0")
                        .sigMapPrefixes(uniqueWithFullPrefixesFor("hollow0"))
                        .via("finalizationBySuccess"),
                cryptoTransfer(moving(1, "unassociatedToken").between("hollow1", "hollow0"))
                        .fee(ONE_HBAR)
                        .payingWith("hollow1")
                        .signedBy("hollow1")
                        .sigMapPrefixes(uniqueWithFullPrefixesFor("hollow1"))
                        .via("finalizationByFailure")
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE));
    }

    private static VisibleItemsValidator reversibleUserValidator() {
        return (spec, records) -> {
            final var successItems = requireNonNull(records.get("firstCreation"), "firstCreation not found");
            assertScheduledItemsMatch(successItems, 0, 1, ScheduleCreate, CryptoTransfer);
            final var duplicateItems = requireNonNull(records.get("duplicateCreation"), "duplicateCreation not found");
            assertScheduledItemsMatch(duplicateItems, 0, -1, ScheduleCreate);
            final var creation = successItems.getFirst();
            final var duplicate = duplicateItems.getFirst();
            assertEquals(creation.createdScheduleId(), duplicate.createdScheduleId());
            assertEquals(creation.scheduledTransactionId(), duplicate.scheduledTransactionId());
        };
    }

    private static VisibleItemsValidator reversibleScheduleValidator() {
        return (spec, records) -> {
            final var committedItems = requireNonNull(records.get("committed"), "committed not found");
            assertScheduledItemsMatch(committedItems, 0, 1, ScheduleCreate, CryptoTransfer);
            assertStatuses(committedItems, SUCCESS, SUCCESS);
            final var rolledBackItems = requireNonNull(records.get("rolledBack"), "rolledBack not found");
            assertScheduledItemsMatch(rolledBackItems, 0, 1, ScheduleCreate, CryptoTransfer);
            assertStatuses(rolledBackItems, SUCCESS, INSUFFICIENT_PAYER_BALANCE);
        };
    }

    private static VisibleItemsValidator reversibleChildValidator() {
        return (spec, records) -> {
            final var successItems = requireNonNull(records.get("fullSuccess"), "fullSuccess not found");
            assertItemsMatch(successItems, 0, ContractCall, CryptoTransfer);
            assertStatuses(successItems, SUCCESS, SUCCESS);
            final var containedRevert = requireNonNull(records.get("containedRevert"), "containedRevert not found");
            assertItemsMatch(containedRevert, 0, ContractCall, CryptoTransfer);
            assertStatuses(containedRevert, SUCCESS, REVERTED_SUCCESS);
            final var fullRevert = requireNonNull(records.get("fullRevert"), "fullRevert not found");
            assertItemsMatch(fullRevert, 0, ContractCall, CryptoTransfer);
            assertStatuses(fullRevert, CONTRACT_REVERT_EXECUTED, REVERTED_SUCCESS);
        };
    }

    private static VisibleItemsValidator removableChildValidator() {
        return (spec, records) -> {
            final var nestedCreations = requireNonNull(records.get("nestedCreations"), "nestedCreations not found");
            assertItemsMatch(nestedCreations, 0, ContractCall, ContractCreate, ContractCreate);
            assertStatuses(nestedCreations, SUCCESS, SUCCESS, SUCCESS);
            final var revertedCreations =
                    requireNonNull(records.get("revertedCreations"), "revertedCreations not found");
            assertItemsMatch(revertedCreations, 0, ContractCall);
            assertStatuses(revertedCreations, CONTRACT_REVERT_EXECUTED);
        };
    }

    private static VisibleItemsValidator irreversiblePrecedingValidator() {
        return (spec, records) -> {
            final var successFinalization =
                    requireNonNull(records.get("finalizationBySuccess"), "finalizationBySuccess not found");
            assertItemsMatch(successFinalization, 1, CryptoUpdate, CryptoTransfer);
            assertStatuses(successFinalization, SUCCESS, SUCCESS);
            final var failFinalization =
                    requireNonNull(records.get("finalizationByFailure"), "finalizationByFailure not found");
            assertItemsMatch(failFinalization, 1, CryptoUpdate, CryptoTransfer);
            assertStatuses(failFinalization, SUCCESS, INSUFFICIENT_TOKEN_BALANCE);
        };
    }

    private static void assertScheduledItemsMatch(
            @NonNull final VisibleItems items,
            final int userTxnIndex,
            final int triggeredTxnIndex,
            @NonNull final HederaFunctionality... functions) {
        assertArrayEquals(functions, items.functions());
        assertParentChildStructure(items, userTxnIndex, triggeredTxnIndex);
    }

    private static void assertItemsMatch(
            @NonNull final VisibleItems items,
            final int userTxnIndex,
            @NonNull final HederaFunctionality... functions) {
        assertArrayEquals(functions, items.functions());
        assertParentChildStructure(items, userTxnIndex, -1);
    }

    private static void assertStatuses(
            @NonNull final VisibleItems items,
            @NonNull final com.hederahashgraph.api.proto.java.ResponseCodeEnum... expected) {
        assertArrayEquals(expected, items.statuses());
    }

    /**
     * Asserts the given stream items, which should all be for a single user transaction, have the expected:
     * <ol>
     *     <li>Consensus time offsets</li>
     *     <li>Nonces</li>
     *     <li>Following child parent consensus times</li>
     * </ol>
     *
     * @param items the items for the user transaction
     * @param numPrecedingChildren the number of preceding children expected
     * @param triggeredTxnIndex if not -1, the index of the triggered transaction
     */
    private static void assertParentChildStructure(
            @NonNull final VisibleItems items, final int numPrecedingChildren, final int triggeredTxnIndex) {
        final var userConsensusTime = items.get(numPrecedingChildren).consensusTime();
        final var userTransactionID = items.get(numPrecedingChildren).txnId();
        int nextExpectedNonce = items.firstExpectedUserNonce();
        for (int i = 0; i < numPrecedingChildren; i++) {
            final var preceding = items.get(i);
            assertEquals(userConsensusTime.minusNanos((numPrecedingChildren - i)), preceding.consensusTime());
            assertEquals(withNonce(userTransactionID, nextExpectedNonce++), preceding.txnId());
        }
        int postTriggeredOffset = 0;
        for (int i = numPrecedingChildren + 1; i < items.size(); i++) {
            final var following = items.get(i);
            assertEquals(userConsensusTime.plusNanos(i - numPrecedingChildren), following.consensusTime());
            if (i == triggeredTxnIndex) {
                assertEquals(withScheduled(userTransactionID), following.txnId());
                postTriggeredOffset++;
            } else {
                assertEquals(
                        withNonce(userTransactionID, nextExpectedNonce++ - postTriggeredOffset), following.txnId());
                assertEquals(userConsensusTime, following.parentConsensusTimestamp());
            }
            if (following.txnId().getScheduled()) {
                assertTrue(following.txnRecord().getReceipt().hasExchangeRate());
            }
        }
    }

    private static TransactionID withNonce(@NonNull final TransactionID parentId, final int nonce) {
        return TransactionID.newBuilder()
                .setAccountID(parentId.getAccountID())
                .setTransactionValidStart(parentId.getTransactionValidStart())
                .setNonce(nonce)
                .build();
    }

    private static TransactionID withScheduled(@NonNull final TransactionID parentId) {
        return TransactionID.newBuilder()
                .setAccountID(parentId.getAccountID())
                .setTransactionValidStart(parentId.getTransactionValidStart())
                .setScheduled(true)
                .build();
    }
}
