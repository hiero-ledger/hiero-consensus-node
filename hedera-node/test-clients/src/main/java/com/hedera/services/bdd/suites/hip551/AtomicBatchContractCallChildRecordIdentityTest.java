// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.spec.HapiPropertySource.explicitBytesOf;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ScheduleInfo;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that a synthetic account-creation record produced by a {@code ContractCall} inside an Atomic Batch is
 * filed under the identity of the inner transaction that caused it.
 * <p>
 * Lazy account creation is dispatched in the
 * {@link com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory#PRECEDING} category from inside the
 * savepoint every contract transaction opens, so it reaches the record stream only once that transaction commits,
 * landing after its own inner where the alias auto-creation of a {@code CryptoTransfer} lands before its own. The
 * cases below cover both kinds appearing in one batch.
 */
@Tag(ATOMIC_BATCH)
public class AtomicBatchContractCallChildRecordIdentityTest {
    private static final String MAKE_CALLS = "MakeCalls";
    private static final String MULTI_CREATE = "NestedLazyCreateContract";
    private static final String CALL_FN = "makeCallWithAmount";
    private static final String MULTI_CALL_FN = "createTooManyHollowAccounts";

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String EVM_PAYER = "evmPayer";
    private static final String TRANSFER_PAYER = "transferPayer";
    private static final String OTHER_PAYER = "otherPayer";
    private static final String PLAIN_RECEIVER = "plainReceiver";

    private static final long DEPOSIT = ONE_HBAR;

    private static final String CODE_DELEGATION_CONTRACT = "CodeDelegationContract";
    private static final String TRIVIAL_CONTRACT = "CreateTrivial";
    private static final String TRIVIAL_FN = "create";
    private static final String REVERTING_CONTRACT = "InternalCallee";
    private static final String REVERT_FN = "revertWithRevertReason";
    private static final String RELAYER_PAYER = "relayerPayer";
    private static final String ETH_SENDER = "ethSender";
    private static final String AUTHORITY = "delegationAuthority";
    private static final String CODE_DELEGATIONS_ENABLED = "contracts.codeDelegations.enabled";
    private static final long GAS_LIMIT_2M = 2_000_000L;

    private static final String SCHEDULE_CONTRACT = "HIP1215Contract";
    private static final String SCHEDULE_CALL_FN = "scheduleCallExample";
    private static final String SCHEDULE_CALL_ENABLED = "contracts.systemContract.scheduleService.scheduleCall.enabled";
    private static final String THROTTLE_BY_GAS = "contracts.throttle.throttleByGas";
    private static final long SCHEDULE_EXPIRY_SHIFT = 60L;

    // ---------------------------------------------------------------------------------------------------------
    // A batch admits at most one contract operation and only as its last inner, so no sibling can follow it.
    // ---------------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("ContractCall as the final inner transaction")
    class TrailingContractCall {

        @HapiTest
        @DisplayName("Lazy creation is filed under the trailing ContractCall, not under an earlier auto-creation")
        final Stream<DynamicTest> lazyCreationIsFiledUnderTheTrailingContractCall() {
            final var aliasKey = "earlierAliasKey";
            final var hollowKey = "lazyHollowKey";
            final var transferInner = "aliasTransferInner";
            final var evmInner = "trailingEvmInner";
            final var lazyCreatedId = new AtomicReference<AccountID>();
            final var evmRecords = new AtomicReference<List<TransactionRecord>>();

            return hapiTest(
                    commonSetup(),
                    newKeyNamed(aliasKey).shape(SECP_256K1_SHAPE),
                    newKeyNamed(hollowKey).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowKey,
                            address -> blockingOrder(
                                    // The earlier inner auto-creates an aliased account of its own, so two
                                    // synthetic creations are in play and each must land on its own inner
                                    atomicBatch(
                                                    cryptoTransfer(tinyBarsFromAccountToAlias(
                                                                    TRANSFER_PAYER, aliasKey, ONE_HBAR))
                                                            .batchKey(BATCH_OPERATOR)
                                                            .payingWith(TRANSFER_PAYER)
                                                            .via(transferInner),
                                                    lazyCreatingCall(address).via(evmInner))
                                            .signedByPayerAnd(BATCH_OPERATOR),
                                    createdAccount(address, lazyCreatedId),
                                    // Each inner owns exactly the one creation it caused
                                    getTxnRecord(transferInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(1),
                                    getTxnRecord(evmInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(1)
                                            .exposingAllTo(evmRecords::set),
                                    assertOwnership(evmRecords, lazyCreatedId, evmInner, transferInner))));
        }

        @HapiTest
        @DisplayName("Two earlier auto-creations do not confuse the trailing ContractCall")
        final Stream<DynamicTest> twoEarlierAutoCreationsDoNotConfuseTheTrailingContractCall() {
            final var aliasOne = "aliasKeyOne";
            final var aliasTwo = "aliasKeyTwo";
            final var hollowKey = "lazyHollowKey";
            final var firstInner = "firstTransferInner";
            final var secondInner = "secondTransferInner";
            final var evmInner = "trailingEvmInner";
            final var lazyCreatedId = new AtomicReference<AccountID>();
            final var evmRecords = new AtomicReference<List<TransactionRecord>>();

            return hapiTest(
                    commonSetup(),
                    newKeyNamed(aliasOne).shape(SECP_256K1_SHAPE),
                    newKeyNamed(aliasTwo).shape(SECP_256K1_SHAPE),
                    newKeyNamed(hollowKey).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowKey,
                            address -> blockingOrder(
                                    atomicBatch(
                                                    cryptoTransfer(tinyBarsFromAccountToAlias(
                                                                    TRANSFER_PAYER, aliasOne, ONE_HBAR))
                                                            .batchKey(BATCH_OPERATOR)
                                                            .payingWith(TRANSFER_PAYER)
                                                            .via(firstInner),
                                                    cryptoTransfer(tinyBarsFromAccountToAlias(
                                                                    OTHER_PAYER, aliasTwo, ONE_HBAR))
                                                            .batchKey(BATCH_OPERATOR)
                                                            .payingWith(OTHER_PAYER)
                                                            .via(secondInner),
                                                    lazyCreatingCall(address).via(evmInner))
                                            .signedByPayerAnd(BATCH_OPERATOR),
                                    createdAccount(address, lazyCreatedId),
                                    getTxnRecord(firstInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(1),
                                    getTxnRecord(secondInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(1),
                                    getTxnRecord(evmInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(1)
                                            .exposingAllTo(evmRecords::set),
                                    assertOwnership(evmRecords, lazyCreatedId, evmInner, secondInner))));
        }

        @HapiTest
        @DisplayName("A lone ContractCall inner owns its lazy creation, not the outer batch")
        final Stream<DynamicTest> loneContractCallInnerOwnsItsLazyCreation() {
            final var hollowKey = "lazyHollowKey";
            final var evmInner = "onlyEvmInner";
            final var lazyCreatedId = new AtomicReference<AccountID>();
            final var evmRecords = new AtomicReference<List<TransactionRecord>>();
            final var outerBatch = "outerBatch";

            return hapiTest(
                    commonSetup(),
                    newKeyNamed(hollowKey).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowKey,
                            address -> blockingOrder(
                                    atomicBatch(lazyCreatingCall(address).via(evmInner))
                                            .payingWith(BATCH_OPERATOR)
                                            .signedByPayerAnd(BATCH_OPERATOR)
                                            .via(outerBatch),
                                    createdAccount(address, lazyCreatedId),
                                    // The creation belongs to the inner transaction, not to the enclosing batch
                                    getTxnRecord(outerBatch)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(0),
                                    getTxnRecord(evmInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(1)
                                            .exposingAllTo(evmRecords::set),
                                    withOpContext((spec, opLog) -> {
                                        final var evmId = spec.registry().getTxnId(evmInner);
                                        final var creation = onlyCreationOf(evmRecords, lazyCreatedId);
                                        assertIdentity(creation.getTransactionID(), evmId);
                                    }))));
        }

        @HapiTest
        @DisplayName("A ContractCall that lazy-creates several accounts owns all of them")
        final Stream<DynamicTest> contractCallOwnsEveryAccountItLazyCreates() {
            final var aliasKey = "earlierAliasKey";
            final var hollowOne = "hollowKeyOne";
            final var hollowTwo = "hollowKeyTwo";
            final var transferInner = "aliasTransferInner";
            final var evmInner = "trailingEvmInner";
            final var evmRecords = new AtomicReference<List<TransactionRecord>>();

            return hapiTest(
                    commonSetup(),
                    uploadInitCode(MULTI_CREATE),
                    contractCreate(MULTI_CREATE).gas(6_000_000L),
                    newKeyNamed(aliasKey).shape(SECP_256K1_SHAPE),
                    newKeyNamed(hollowOne).shape(SECP_256K1_SHAPE),
                    newKeyNamed(hollowTwo).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowOne,
                            first -> withAddressOfKey(
                                    hollowTwo,
                                    second -> blockingOrder(
                                            atomicBatch(
                                                            cryptoTransfer(
                                                                            tinyBarsFromAccountToAlias(
                                                                                    TRANSFER_PAYER, aliasKey, ONE_HBAR))
                                                                    .batchKey(BATCH_OPERATOR)
                                                                    .payingWith(TRANSFER_PAYER)
                                                                    .via(transferInner),
                                                            contractCall(
                                                                            MULTI_CREATE,
                                                                            MULTI_CALL_FN,
                                                                            (Object) new Address[] {first, second})
                                                                    .batchKey(BATCH_OPERATOR)
                                                                    .payingWith(EVM_PAYER)
                                                                    .gas(2_000_000L)
                                                                    .sending(2 * DEPOSIT)
                                                                    .via(evmInner))
                                                    .signedByPayerAnd(BATCH_OPERATOR),
                                            getTxnRecord(transferInner)
                                                    .andAllChildRecords()
                                                    .hasNonStakingChildRecordCount(1),
                                            // Both hollow accounts belong to the call that created them
                                            getTxnRecord(evmInner)
                                                    .andAllChildRecords()
                                                    .hasNonStakingChildRecordCount(2)
                                                    .exposingAllTo(evmRecords::set),
                                            withOpContext((spec, opLog) -> {
                                                final var evmId =
                                                        spec.registry().getTxnId(evmInner);
                                                final var creations = evmRecords.get().stream()
                                                        .filter(
                                                                record -> record.getReceipt()
                                                                        .hasAccountID())
                                                        .toList();
                                                assertEquals(
                                                        2,
                                                        creations.size(),
                                                        "both creations should belong to the call");
                                                creations.forEach(
                                                        record -> assertIdentity(record.getTransactionID(), evmId));
                                                assertEquals(
                                                        2,
                                                        creations.stream()
                                                                .map(
                                                                        record -> record.getTransactionID()
                                                                                .getNonce())
                                                                .distinct()
                                                                .count(),
                                                        "the two creations should carry distinct nonces");
                                            })))));
        }

        @HapiTest
        @DisplayName("A failing trailing ContractCall rolls its lazy creation back entirely")
        final Stream<DynamicTest> failingTrailingContractCallRollsBackItsLazyCreation() {
            final var aliasKey = "earlierAliasKey";
            final var hollowKey = "lazyHollowKey";
            final var transferInner = "aliasTransferInner";

            return hapiTest(
                    commonSetup(),
                    newKeyNamed(aliasKey).shape(SECP_256K1_SHAPE),
                    newKeyNamed(hollowKey).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowKey,
                            address -> blockingOrder(
                                    atomicBatch(
                                                    cryptoTransfer(tinyBarsFromAccountToAlias(
                                                                    TRANSFER_PAYER, aliasKey, ONE_HBAR))
                                                            .batchKey(BATCH_OPERATOR)
                                                            .payingWith(TRANSFER_PAYER)
                                                            .via(transferInner),
                                                    // Too little gas for the lazy creation, so the inner txn fails
                                                    contractCall(MAKE_CALLS, CALL_FN, address, new byte[0])
                                                            .batchKey(BATCH_OPERATOR)
                                                            .payingWith(EVM_PAYER)
                                                            .gas(25_000L)
                                                            .sending(1L)
                                                            .hasKnownStatus(INSUFFICIENT_GAS))
                                            .signedByPayerAnd(BATCH_OPERATOR)
                                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                                    // The whole batch rolled back, so neither account survives
                                    getAliasedAccountInfo(aliasKey).hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                                    aliasIsAbsent(address))));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Baselines: paths the fix must leave exactly as they were
    // ---------------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Baselines outside the batch attribution logic")
    class Baselines {

        @HapiTest
        @DisplayName("A ContractCall outside any batch still owns its lazy creation")
        final Stream<DynamicTest> nonBatchContractCallStillOwnsItsLazyCreation() {
            final var hollowKey = "lazyHollowKey";
            final var call = "plainCall";
            final var lazyCreatedId = new AtomicReference<AccountID>();
            final var callRecords = new AtomicReference<List<TransactionRecord>>();

            return hapiTest(
                    commonSetup(),
                    newKeyNamed(hollowKey).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowKey,
                            address -> blockingOrder(
                                    contractCall(MAKE_CALLS, CALL_FN, address, new byte[0])
                                            .payingWith(EVM_PAYER)
                                            .gas(1_000_000L)
                                            .sending(DEPOSIT)
                                            .via(call),
                                    createdAccount(address, lazyCreatedId),
                                    getTxnRecord(call)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(1)
                                            .exposingAllTo(callRecords::set),
                                    withOpContext((spec, opLog) -> {
                                        final var callId = spec.registry().getTxnId(call);
                                        assertIdentity(
                                                onlyCreationOf(callRecords, lazyCreatedId)
                                                        .getTransactionID(),
                                                callId);
                                    }))));
        }

        @HapiTest
        @DisplayName("Alias auto-creation in a batch inner stays under that inner")
        final Stream<DynamicTest> aliasAutoCreationStaysUnderItsOwnInner() {
            final var aliasKey = "earlierAliasKey";
            final var transferInner = "aliasTransferInner";
            final var otherInner = "otherInner";
            final var transferRecords = new AtomicReference<List<TransactionRecord>>();

            return hapiTest(
                    commonSetup(),
                    newKeyNamed(aliasKey).shape(SECP_256K1_SHAPE),
                    atomicBatch(
                                    cryptoTransfer(tinyBarsFromAccountToAlias(TRANSFER_PAYER, aliasKey, ONE_HBAR))
                                            .batchKey(BATCH_OPERATOR)
                                            .payingWith(TRANSFER_PAYER)
                                            .via(transferInner),
                                    cryptoTransfer(movingHbar(1L).between(OTHER_PAYER, PLAIN_RECEIVER))
                                            .batchKey(BATCH_OPERATOR)
                                            .payingWith(OTHER_PAYER)
                                            .via(otherInner))
                            .signedByPayerAnd(BATCH_OPERATOR),
                    getTxnRecord(otherInner).andAllChildRecords().hasNonStakingChildRecordCount(0),
                    getTxnRecord(transferInner)
                            .andAllChildRecords()
                            .hasNonStakingChildRecordCount(1)
                            .exposingAllTo(transferRecords::set),
                    withOpContext((spec, opLog) -> {
                        final var transferId = spec.registry().getTxnId(transferInner);
                        final var creations = transferRecords.get().stream()
                                .filter(record -> record.getReceipt().hasAccountID())
                                .toList();
                        assertEquals(1, creations.size(), "the auto-creation belongs to its own inner");
                        assertIdentity(creations.getFirst().getTransactionID(), transferId);
                    }));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Dispatch paths whose owning inner transaction cannot be read off the savepoint stack,
    // and so is carried explicitly instead. Both classes below pin down such a case.
    // ---------------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Code delegations replayed after a batch rollback")
    class CodeDelegationRollbackReplay {

        @LeakyHapiTest(overrides = {CODE_DELEGATIONS_ENABLED})
        @DisplayName("A code delegation replayed after the Ethereum inner itself reverts belongs to that inner")
        final Stream<DynamicTest> replayAfterOwnFailureBelongsToTheEthereumInner() {
            final var ethInner = "revertingEthInner";
            final var outerBatch = "revertedBatch";
            final var innerRecords = new AtomicReference<List<TransactionRecord>>();
            final var batchRecords = new AtomicReference<List<TransactionRecord>>();
            final var delegationTarget = new AtomicReference<Address>();

            return hapiTest(
                    overriding(CODE_DELEGATIONS_ENABLED, "true"),
                    commonSetup(),
                    delegationSetup(delegationTarget),
                    withOpContext((spec, opLog) -> allRunFor(
                            spec,
                            // The Ethereum inner is itself the failure; the authorization is still applied before
                            // the call reverts, so the batch handler replays it on the way out
                            atomicBatch(delegatingEthCall(delegationTarget, REVERTING_CONTRACT, REVERT_FN)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .via(ethInner))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED)
                                    .via(outerBatch))),
                    getTxnRecord(ethInner).andAllChildRecords().exposingAllTo(innerRecords::set),
                    getTxnRecord(outerBatch).andAllChildRecords().exposingAllTo(batchRecords::set),
                    assertReplayOwnership(innerRecords, batchRecords, ethInner));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // A dispatch that arrives with a preset transaction id is stamped before the handler runs,
    // so its identity is resolved when the id is issued rather than when the record is built
    // ---------------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Preset-id children dispatched by a batch inner")
    class PresetIdChildren {

        @LeakyHapiTest(overrides = {SCHEDULE_CALL_ENABLED, THROTTLE_BY_GAS})
        @DisplayName("A scheduleCall from a ContractCall inner is owned by that inner, in record and in state")
        final Stream<DynamicTest> scheduleCallFromAnInnerIsOwnedByThatInner() {
            final var ccInner = "schedulingInner";
            final var outerBatch = "schedulingBatch";
            final var innerRecords = new AtomicReference<List<TransactionRecord>>();
            final var batchRecords = new AtomicReference<List<TransactionRecord>>();

            return hapiTest(
                    overriding(SCHEDULE_CALL_ENABLED, "true"),
                    overriding(THROTTLE_BY_GAS, "false"),
                    commonSetup(),
                    uploadInitCode(SCHEDULE_CONTRACT),
                    contractCreate(SCHEDULE_CONTRACT).gas(4_000_000L),
                    atomicBatch(contractCall(
                                            SCHEDULE_CONTRACT,
                                            SCHEDULE_CALL_FN,
                                            BigInteger.valueOf(SCHEDULE_EXPIRY_SHIFT))
                                    .batchKey(BATCH_OPERATOR)
                                    .payingWith(EVM_PAYER)
                                    .gas(GAS_LIMIT_2M)
                                    .via(ccInner))
                            .payingWith(BATCH_OPERATOR)
                            .signedByPayerAnd(BATCH_OPERATOR)
                            .via(outerBatch),
                    getTxnRecord(ccInner).andAllChildRecords().exposingAllTo(innerRecords::set),
                    getTxnRecord(outerBatch).andAllChildRecords().exposingAllTo(batchRecords::set),
                    withOpContext((spec, opLog) -> {
                        final var underInner = scheduleCreationsIn(innerRecords);
                        final var underBatch = scheduleCreationsIn(batchRecords);
                        assertEquals(
                                1,
                                underInner.size() + underBatch.size(),
                                "expected exactly one ScheduleCreate record from the scheduleCall");
                        final var creation = underInner.isEmpty() ? underBatch.getFirst() : underInner.getFirst();
                        final var info = new AtomicReference<ScheduleInfo>();
                        allRunFor(
                                spec,
                                getScheduleInfo(String.valueOf(creation.getReceipt()
                                                .getScheduleID()
                                                .getScheduleNum()))
                                        .exposingInfoTo(info::set));
                        final var innerId = spec.registry().getTxnId(ccInner);
                        opLog.info(
                                "scheduleCreate filed under inner={} batch={} creator={}",
                                underInner.size(),
                                underBatch.size(),
                                info.get().getCreatorAccountID());
                        assertAll(
                                () -> assertTrue(
                                        underBatch.isEmpty(),
                                        "the ScheduleCreate record must not be filed under the enclosing batch"),
                                () -> assertIdentity(creation.getTransactionID(), innerId),
                                () -> assertEquals(
                                        spec.registry().getAccountID(EVM_PAYER),
                                        info.get().getCreatorAccountID(),
                                        "the stored schedule must be created by the inner transaction's payer,"
                                                + " not the batch payer"));
                    }));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private static SpecOperation commonSetup() {
        return blockingOrder(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(EVM_PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TRANSFER_PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER_PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PLAIN_RECEIVER).balance(0L),
                uploadInitCode(MAKE_CALLS),
                contractCreate(MAKE_CALLS).gas(3_000_000L));
    }

    /** A ContractCall that sends value to a brand new EVM address, lazy-creating a hollow account for it. */
    private static com.hedera.services.bdd.spec.transactions.contract.HapiContractCall lazyCreatingCall(
            final Address address) {
        return contractCall(MAKE_CALLS, CALL_FN, address, new byte[0])
                .batchKey(BATCH_OPERATOR)
                .payingWith(EVM_PAYER)
                .gas(1_000_000L)
                .sending(DEPOSIT);
    }

    private static SpecOperation createdAccount(final Address address, final AtomicReference<AccountID> sink) {
        return getAliasedAccountInfo(ByteString.copyFrom(explicitBytesOf(address)))
                .has(accountWith().balance(DEPOSIT))
                .exposingIdTo(sink::set);
    }

    private static SpecOperation aliasIsAbsent(final Address address) {
        return getAliasedAccountInfo(ByteString.copyFrom(explicitBytesOf(address)))
                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID);
    }

    /** Asserts the single creation exposed under {@code owner} carries its identity, not {@code other}'s. */
    private static SpecOperation assertOwnership(
            final AtomicReference<List<TransactionRecord>> ownerRecords,
            final AtomicReference<AccountID> createdId,
            final String owner,
            final String other) {
        return withOpContext((spec, opLog) -> {
            final var ownerTxnId = spec.registry().getTxnId(owner);
            final var otherTxnId = spec.registry().getTxnId(other);
            final var creation = onlyCreationOf(ownerRecords, createdId);
            opLog.info(
                    "attribution: owner={} other={} creationId={} createdAccount={}",
                    ownerTxnId,
                    otherTxnId,
                    creation.getTransactionID(),
                    creation.getReceipt().getAccountID());
            assertIdentity(creation.getTransactionID(), ownerTxnId);
            assertNotEquals(
                    otherTxnId.getAccountID(),
                    creation.getTransactionID().getAccountID(),
                    "the creation must not carry the other inner transaction's payer");
        });
    }

    private static TransactionRecord onlyCreationOf(
            final AtomicReference<List<TransactionRecord>> records, final AtomicReference<AccountID> createdId) {
        final var matches = records.get().stream()
                .filter(record -> record.getReceipt().hasAccountID()
                        && record.getReceipt().getAccountID().equals(createdId.get()))
                .toList();
        assertEquals(1, matches.size(), "expected exactly one creation record for " + createdId.get());
        return matches.getFirst();
    }

    private static void assertIdentity(final TransactionID creationId, final TransactionID ownerId) {
        assertEquals(ownerId.getAccountID(), creationId.getAccountID(), "creation should carry the owner's payer");
        assertEquals(
                ownerId.getTransactionValidStart(),
                creationId.getTransactionValidStart(),
                "creation should carry the owner's valid start");
        // The nonce comes from a counter shared by every synthetic record in the user transaction, so only its
        // being a child nonce is meaningful
        assertTrue(creationId.getNonce() > 0, "creation should carry a child nonce");
    }

    /** Uploads the delegation target and call targets, and funds the accounts an EIP-7702 call needs. */
    private static SpecOperation delegationSetup(final AtomicReference<Address> delegationTarget) {
        return blockingOrder(
                uploadInitCode(CODE_DELEGATION_CONTRACT),
                contractCreate(CODE_DELEGATION_CONTRACT).exposingAddressTo(delegationTarget::set),
                uploadInitCode(TRIVIAL_CONTRACT),
                contractCreate(TRIVIAL_CONTRACT).gas(3_000_000L),
                uploadInitCode(REVERTING_CONTRACT),
                contractCreate(REVERTING_CONTRACT).gas(3_000_000L),
                cryptoCreate(RELAYER_PAYER).balance(ONE_MILLION_HBARS),
                fundedEcdsaAccount(ETH_SENDER),
                fundedEcdsaAccount(AUTHORITY));
    }

    private static SpecOperation fundedEcdsaAccount(final String name) {
        return blockingOrder(
                newKeyNamed(name).shape(SECP_256K1_SHAPE),
                cryptoCreate(name).key(name).withMatchingEvmAddress().balance(ONE_HUNDRED_HBARS));
    }

    /**
     * An EIP-7702 call delegating {@code AUTHORITY} to the target contract; the authority already exists, so the
     * replay after a rollback takes the {@code CryptoUpdate} branch.
     */
    private static com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall delegatingEthCall(
            final AtomicReference<Address> delegationTarget, final String contract, final String function) {
        return ethereumCall(contract, function)
                .signingWith(ETH_SENDER)
                .payingWith(RELAYER_PAYER)
                .type(EthTransactionType.EIP7702)
                .addCodeDelegationWithSpecNonce(delegationTarget.get(), AUTHORITY)
                .gasLimit(GAS_LIMIT_2M)
                .batchKey(BATCH_OPERATOR);
    }

    /**
     * Asserts the records the rollback replay produced are filed under the Ethereum inner that caused the
     * delegation, and not under the enclosing batch.
     */
    private static SpecOperation assertReplayOwnership(
            final AtomicReference<List<TransactionRecord>> innerRecords,
            final AtomicReference<List<TransactionRecord>> batchRecords,
            final String ethInner) {
        return withOpContext((spec, opLog) -> {
            final var ethId = spec.registry().getTxnId(ethInner);
            final var underInner = syntheticRecordsIn(innerRecords);
            final var underBatch = syntheticRecordsIn(batchRecords);
            opLog.info(
                    "code-delegation replay: {} record(s) under the Ethereum inner, {} under the batch",
                    underInner.size(),
                    underBatch.size());
            assertAll(
                    () -> assertTrue(
                            underBatch.isEmpty(),
                            "replayed code-delegation records must not be filed under the enclosing batch, but "
                                    + underBatch.size() + " were"),
                    () -> assertEquals(
                            1,
                            underInner.size(),
                            "the Ethereum inner should own exactly one replayed code-delegation record"),
                    () -> underInner.forEach(record -> assertIdentity(record.getTransactionID(), ethId)));
        });
    }

    /** The synthetic records in an exposed list; index 0 is the parent, and every child carries a non-zero nonce. */
    private static List<TransactionRecord> syntheticRecordsIn(final AtomicReference<List<TransactionRecord>> records) {
        return records.get().stream()
                .filter(record -> record.getTransactionID().getNonce() > 0)
                .filter(TxnUtils::isNotEndOfStakingPeriodRecord)
                .toList();
    }

    private static List<TransactionRecord> scheduleCreationsIn(final AtomicReference<List<TransactionRecord>> records) {
        return records.get().stream()
                .filter(record -> record.getReceipt().hasScheduleID())
                .toList();
    }
}
