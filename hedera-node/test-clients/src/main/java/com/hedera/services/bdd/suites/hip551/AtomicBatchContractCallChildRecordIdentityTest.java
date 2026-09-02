// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.spec.HapiPropertySource.explicitBytesOf;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that a synthetic account-creation record produced by a {@code ContractCall} inside an Atomic Batch is
 * filed under the identity of the inner transaction that actually caused it, across every arrangement of
 * {@code ContractCall} within a batch.
 * <p>
 * The EVM's lazy account creation is dispatched in the
 * {@link com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory#PRECEDING} category from inside the
 * savepoint every contract transaction opens, so it is only flushed into the record stream when the EVM transaction
 * commits. It therefore lands <i>after</i> the inner transaction that produced it, unlike the alias auto-creation of
 * a {@code CryptoTransfer}, which lands before. Ownership consequently cannot be inferred from position, and the
 * cases below pin down every arrangement where that mattered.
 * <p>
 * <b>Note on the nested groups.</b> {@link TrailingContractCall} holds arrangements that remain legal under builds
 * that restrict a batch to a single EVM transaction which must be the final inner transaction. {@link AnyPosition}
 * holds arrangements that such a build rejects with {@code INVALID_TRANSACTION_BODY} before reaching the record
 * stream; run that group only where the restriction is absent.
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

    // ---------------------------------------------------------------------------------------------------------
    // Arrangements that remain legal when a batch may hold only one EVM transaction, in final position
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
                                    // The earlier inner auto-creates an aliased account of its own, which is what used
                                    // to
                                    // leave a stale identity behind for the trailing call to inherit
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
                                    // Before the fix this creation was filed under the OUTER batch transaction
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
                                        assertEquals(
                                                evmRecords.get().getFirst().getConsensusTimestamp(),
                                                creation.getParentConsensusTimestamp(),
                                                "the creation should report its inner transaction as its parent");
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
    // Arrangements a single-trailing-EVM-transaction build rejects up front; run only where that is not enforced
    // ---------------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("ContractCall in any position (requires no single-trailing-EVM restriction)")
    class AnyPosition {

        @HapiTest
        @DisplayName("Lazy creation from a leading ContractCall is not filed under the following inner")
        final Stream<DynamicTest> lazyCreationFromLeadingContractCallIsNotFiledUnderTheFollower() {
            final var hollowKey = "lazyHollowKey";
            final var evmInner = "leadingEvmInner";
            final var followerInner = "followingInner";
            final var lazyCreatedId = new AtomicReference<AccountID>();
            final var evmRecords = new AtomicReference<List<TransactionRecord>>();

            return hapiTest(
                    commonSetup(),
                    newKeyNamed(hollowKey).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowKey,
                            address -> blockingOrder(
                                    atomicBatch(
                                                    lazyCreatingCall(address).via(evmInner),
                                                    cryptoTransfer(movingHbar(1L)
                                                                    .between(OTHER_PAYER, PLAIN_RECEIVER))
                                                            .batchKey(BATCH_OPERATOR)
                                                            .payingWith(OTHER_PAYER)
                                                            .via(followerInner))
                                            .signedByPayerAnd(BATCH_OPERATOR),
                                    createdAccount(address, lazyCreatedId),
                                    getTxnRecord(followerInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(0),
                                    getTxnRecord(evmInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(1)
                                            .exposingAllTo(evmRecords::set),
                                    assertOwnership(evmRecords, lazyCreatedId, evmInner, followerInner))));
        }

        @HapiTest
        @DisplayName("Lazy creation from a middle ContractCall is filed under that call")
        final Stream<DynamicTest> lazyCreationFromMiddleContractCallIsFiledUnderThatCall() {
            final var hollowKey = "lazyHollowKey";
            final var firstInner = "firstInner";
            final var evmInner = "middleEvmInner";
            final var lastInner = "lastInner";
            final var lazyCreatedId = new AtomicReference<AccountID>();
            final var evmRecords = new AtomicReference<List<TransactionRecord>>();

            return hapiTest(
                    commonSetup(),
                    newKeyNamed(hollowKey).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowKey,
                            address -> blockingOrder(
                                    atomicBatch(
                                                    cryptoTransfer(movingHbar(1L)
                                                                    .between(TRANSFER_PAYER, PLAIN_RECEIVER))
                                                            .batchKey(BATCH_OPERATOR)
                                                            .payingWith(TRANSFER_PAYER)
                                                            .via(firstInner),
                                                    lazyCreatingCall(address).via(evmInner),
                                                    cryptoTransfer(movingHbar(1L)
                                                                    .between(OTHER_PAYER, PLAIN_RECEIVER))
                                                            .batchKey(BATCH_OPERATOR)
                                                            .payingWith(OTHER_PAYER)
                                                            .via(lastInner))
                                            .signedByPayerAnd(BATCH_OPERATOR),
                                    createdAccount(address, lazyCreatedId),
                                    getTxnRecord(firstInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(0),
                                    getTxnRecord(lastInner).andAllChildRecords().hasNonStakingChildRecordCount(0),
                                    getTxnRecord(evmInner)
                                            .andAllChildRecords()
                                            .hasNonStakingChildRecordCount(1)
                                            .exposingAllTo(evmRecords::set),
                                    assertOwnership(evmRecords, lazyCreatedId, evmInner, lastInner))));
        }

        @HapiTest
        @DisplayName("Two ContractCalls each own the account they lazy-created")
        final Stream<DynamicTest> twoContractCallsEachOwnTheirOwnLazyCreation() {
            final var hollowOne = "hollowKeyOne";
            final var hollowTwo = "hollowKeyTwo";
            final var firstEvm = "firstEvmInner";
            final var secondEvm = "secondEvmInner";
            final var firstCreated = new AtomicReference<AccountID>();
            final var secondCreated = new AtomicReference<AccountID>();
            final var firstRecords = new AtomicReference<List<TransactionRecord>>();
            final var secondRecords = new AtomicReference<List<TransactionRecord>>();

            return hapiTest(
                    commonSetup(),
                    newKeyNamed(hollowOne).shape(SECP_256K1_SHAPE),
                    newKeyNamed(hollowTwo).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowOne,
                            first -> withAddressOfKey(
                                    hollowTwo,
                                    second -> blockingOrder(
                                            atomicBatch(
                                                            lazyCreatingCall(first)
                                                                    .payingWith(EVM_PAYER)
                                                                    .via(firstEvm),
                                                            contractCall(MAKE_CALLS, CALL_FN, second, new byte[0])
                                                                    .batchKey(BATCH_OPERATOR)
                                                                    .payingWith(OTHER_PAYER)
                                                                    .gas(1_000_000L)
                                                                    .sending(DEPOSIT)
                                                                    .via(secondEvm))
                                                    .signedByPayerAnd(BATCH_OPERATOR),
                                            createdAccount(first, firstCreated),
                                            createdAccount(second, secondCreated),
                                            getTxnRecord(firstEvm)
                                                    .andAllChildRecords()
                                                    .hasNonStakingChildRecordCount(1)
                                                    .exposingAllTo(firstRecords::set),
                                            getTxnRecord(secondEvm)
                                                    .andAllChildRecords()
                                                    .hasNonStakingChildRecordCount(1)
                                                    .exposingAllTo(secondRecords::set),
                                            withOpContext((spec, opLog) -> {
                                                final var firstId =
                                                        spec.registry().getTxnId(firstEvm);
                                                final var secondId =
                                                        spec.registry().getTxnId(secondEvm);
                                                assertIdentity(
                                                        onlyCreationOf(firstRecords, firstCreated)
                                                                .getTransactionID(),
                                                        firstId);
                                                assertIdentity(
                                                        onlyCreationOf(secondRecords, secondCreated)
                                                                .getTransactionID(),
                                                        secondId);
                                            })))));
        }

        @HapiTest
        @DisplayName("A leading ContractCall's lazy creation is rolled back when a later inner fails")
        final Stream<DynamicTest> leadingLazyCreationIsRolledBackWhenALaterInnerFails() {
            final var hollowKey = "lazyHollowKey";
            final var brokeAccount = "brokeAccount";

            return hapiTest(
                    commonSetup(),
                    cryptoCreate(brokeAccount).balance(0L),
                    newKeyNamed(hollowKey).shape(SECP_256K1_SHAPE),
                    withAddressOfKey(
                            hollowKey,
                            address -> blockingOrder(
                                    atomicBatch(
                                                    lazyCreatingCall(address),
                                                    // Cannot cover the transfer, so this inner fails and the batch
                                                    // rolls back
                                                    cryptoTransfer(movingHbar(ONE_HUNDRED_HBARS)
                                                                    .between(brokeAccount, PLAIN_RECEIVER))
                                                            .batchKey(BATCH_OPERATOR)
                                                            .payingWith(BATCH_OPERATOR)
                                                            .signedBy(BATCH_OPERATOR, brokeAccount)
                                                            .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE))
                                            .signedByPayerAnd(BATCH_OPERATOR)
                                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
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

    /** Asserts the single creation exposed under {@code owner} carries {@code owner}'s identity, not {@code other}'s. */
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
            assertEquals(
                    ownerRecords.get().getFirst().getConsensusTimestamp(),
                    creation.getParentConsensusTimestamp(),
                    "the creation should report its own inner transaction as its parent");
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
}
