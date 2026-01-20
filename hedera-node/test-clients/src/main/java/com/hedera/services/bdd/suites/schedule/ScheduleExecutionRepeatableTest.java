// SPDX-License-Identifier: Apache-2.0
//// SPDX-License-Identifier: Apache-2.0
// package com.hedera.services.bdd.suites.schedule;
//
// import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
// import static com.hedera.services.bdd.junit.TestTags.MATS;
// import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
// import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
// import static com.hedera.services.bdd.spec.dsl.operations.transactions.TouchBalancesOperation.touchBalanceOf;
// import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
// import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.invalidBurnToken;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.invalidMintToken;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
// import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
// import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
// import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
// import static com.hedera.services.bdd.suites.hip904.UnlimitedAutoAssociationSuite.UNLIMITED_AUTO_ASSOCIATION_SLOTS;
// import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.A_SCHEDULE;
// import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.A_TOKEN;
// import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.FAILING_TXN;
// import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SCHEDULE_PAYER;
// import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SUPPLY_KEY;
// import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.TREASURY;
// import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
// import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
// import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
// import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
// import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;
//
// import com.google.protobuf.ByteString;
// import com.hedera.services.bdd.junit.HapiTestLifecycle;
// import com.hedera.services.bdd.junit.RepeatableHapiTest;
// import com.hedera.services.bdd.junit.TargetEmbeddedMode;
// import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode;
// import com.hedera.services.bdd.spec.dsl.annotations.Account;
// import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
// import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
// import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
// import com.hederahashgraph.api.proto.java.TokenType;
// import java.util.List;
// import java.util.stream.Stream;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.DynamicTest;
// import org.junit.jupiter.api.Tag;
//
/// **
// * CONVERTED VERSION: This class contains the first 5 tests from {@link ScheduleExecutionTest}
// * converted from {@code @HapiTest} to {@code @RepeatableHapiTest}.
// *
// * <h2>Why Convert to Repeatable Mode?</h2>
// * <ul>
// *   <li><b>Determinism:</b> Tests run with virtual time, making them reproducible</li>
// *   <li><b>Faster execution:</b> No subprocess JVM startup overhead</li>
// *   <li><b>Synchronous handling:</b> Schedule execution happens predictably</li>
// *   <li><b>Better CI reliability:</b> No timing-related flakiness</li>
// * </ul>
// *
// * <h2>Conversion Safety Analysis</h2>
// * <p>These tests are safe to convert because they:</p>
// * <ul>
// *   <li>Do NOT use ECDSA keys (which have random signatures)</li>
// *   <li>Do NOT use {@code inParallel()} operations</li>
// *   <li>Do NOT use {@code setNode()} to target specific nodes</li>
// *   <li>Do NOT require actual gossip or node lifecycle operations</li>
// *   <li>Test pure business logic (schedule creation, signing, execution)</li>
// * </ul>
// *
// * <p>Compare with original tests in {@link ScheduleExecutionTest} to see the difference
// * is only in the annotation change from {@code @HapiTest} to {@code @RepeatableHapiTest}.
// *
// * @see ScheduleExecutionTest
// */
// @HapiTestLifecycle
// @TargetEmbeddedMode(EmbeddedMode.REPEATABLE)
// public class ScheduleExecutionRepeatableTest {
//
//    // =====================================================================================
//    // TEST 1: scheduledBurnFailsWithInvalidTxBody
//    // Original: @HapiTest in ScheduleExecutionTest.java line 146
//    // Conversion: Safe - pure validation logic, no special requirements
//    // =====================================================================================
//
//    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
//    @DisplayName("Repeatable: Scheduled burn fails with invalid tx body")
//    final Stream<DynamicTest> scheduledBurnFailsWithInvalidTxBody() {
//        return hapiTest(
//                cryptoCreate(TREASURY),
//                cryptoCreate(SCHEDULE_PAYER),
//                newKeyNamed(SUPPLY_KEY),
//                tokenCreate(A_TOKEN)
//                        .supplyKey(SUPPLY_KEY)
//                        .treasury(TREASURY)
//                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
//                        .initialSupply(0),
//                scheduleCreate(A_SCHEDULE, invalidBurnToken(A_TOKEN, List.of(1L, 2L), 123))
//                        .designatingPayer(SCHEDULE_PAYER)
//                        .hasKnownStatus(INVALID_TRANSACTION_BODY));
//    }
//
//    // =====================================================================================
//    // TEST 2: scheduledMintFailsWithInvalidTxBody
//    // Original: @HapiTest in ScheduleExecutionTest.java line 162
//    // Conversion: Safe - pure validation logic, no special requirements
//    // =====================================================================================
//
//    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
//    @DisplayName("Repeatable: Scheduled mint fails with invalid tx body")
//    final Stream<DynamicTest> scheduledMintFailsWithInvalidTxBody() {
//        return hapiTest(
//                cryptoCreate(TREASURY),
//                cryptoCreate(SCHEDULE_PAYER),
//                newKeyNamed(SUPPLY_KEY),
//                tokenCreate(A_TOKEN)
//                        .supplyKey(SUPPLY_KEY)
//                        .treasury(TREASURY)
//                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
//                        .initialSupply(0),
//                scheduleCreate(A_SCHEDULE, invalidMintToken(A_TOKEN, List.of(ByteString.copyFromUtf8("m1")), 123))
//                        .hasKnownStatus(INVALID_TRANSACTION_BODY)
//                        .designatingPayer(SCHEDULE_PAYER),
//                getTokenInfo(A_TOKEN).hasTotalSupply(0));
//    }
//
//    // =====================================================================================
//    // TEST 3: scheduledMintWithInvalidTokenThrowsUnresolvableSigners
//    // Original: @HapiTest in ScheduleExecutionTest.java line 179
//    // Conversion: Safe - pure validation logic, no special requirements
//    // =====================================================================================
//
//    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
//    @DisplayName("Repeatable: Scheduled mint with invalid token throws unresolvable signers")
//    final Stream<DynamicTest> scheduledMintWithInvalidTokenThrowsUnresolvableSigners() {
//        return hapiTest(
//                cryptoCreate(SCHEDULE_PAYER),
//                scheduleCreate(
//                                A_SCHEDULE,
//                                mintToken("0.0.123231", List.of(ByteString.copyFromUtf8("m1")))
//                                        .fee(ONE_HBAR))
//                        .designatingPayer(SCHEDULE_PAYER)
//                        .hasKnownStatus(UNRESOLVABLE_REQUIRED_SIGNERS));
//    }
//
//    // =====================================================================================
//    // TEST 4: scheduledUniqueBurnFailsWithInvalidBatchSize
//    // Original: @HapiTest in ScheduleExecutionTest.java line 191
//    // Conversion: Safe - tests batch size validation, no special requirements
//    // =====================================================================================
//
//    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
//    @DisplayName("Repeatable: Scheduled unique burn fails with invalid batch size")
//    final Stream<DynamicTest> scheduledUniqueBurnFailsWithInvalidBatchSize() {
//        return hapiTest(
//                cryptoCreate(TREASURY),
//                cryptoCreate(SCHEDULE_PAYER),
//                newKeyNamed(SUPPLY_KEY),
//                tokenCreate(A_TOKEN)
//                        .supplyKey(SUPPLY_KEY)
//                        .treasury(TREASURY)
//                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
//                        .initialSupply(0),
//                mintToken(A_TOKEN, List.of(ByteString.copyFromUtf8("m1"))),
//                scheduleCreate(
//                                A_SCHEDULE,
//                                burnToken(A_TOKEN, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L)))
//                        .designatingPayer(SCHEDULE_PAYER)
//                        .via(FAILING_TXN),
//                getTokenInfo(A_TOKEN).hasTotalSupply(1),
//                scheduleSign(A_SCHEDULE)
//                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
//                        .hasKnownStatus(SUCCESS),
//                getTxnRecord(FAILING_TXN).scheduled().hasPriority(recordWith().status(BATCH_SIZE_LIMIT_EXCEEDED)),
//                getTokenInfo(A_TOKEN).hasTotalSupply(1));
//    }
//
//    // =====================================================================================
//    // TEST 5: scheduledTxsCanIncurHandlerAssessedFees
//    // Original: @HapiTest @Tag(MATS) in ScheduleExecutionTest.java line 216
//    // Conversion: Safe - uses DSL annotations but no ECDSA or parallelism
//    // Note: This test uses spec-dsl annotations (@FungibleToken, @Account)
//    // =====================================================================================
//
//    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
//    @Tag(MATS)
//    @DisplayName("Repeatable: Scheduled txs can incur handler assessed fees")
//    final Stream<DynamicTest> scheduledTxsCanIncurHandlerAssessedFees(
//            @FungibleToken SpecFungibleToken firstToken,
//            @FungibleToken SpecFungibleToken secondToken,
//            @Account(maxAutoAssociations = 2) SpecAccount firstReceiver,
//            @Account(maxAutoAssociations = 2) SpecAccount secondReceiver,
//            @Account(centBalance = 100, maxAutoAssociations = UNLIMITED_AUTO_ASSOCIATION_SLOTS)
//                    SpecAccount solventPayer,
//            @Account(centBalance = 7, maxAutoAssociations = UNLIMITED_AUTO_ASSOCIATION_SLOTS)
//                    SpecAccount insolventPayer) {
//        return hapiTest(
//                firstToken.treasury().transferUnitsTo(solventPayer, 10, firstToken),
//                secondToken.treasury().transferUnitsTo(insolventPayer, 10, secondToken),
//                // Ensure the receiver entities exist before switching out object-oriented DSL
//                touchBalanceOf(firstReceiver, secondReceiver),
//                // Immediate trigger a schedule dispatch that succeeds as payer can afford two auto-associations
//                scheduleCreate(
//                                "committedTxn",
//                                cryptoTransfer(moving(2, firstToken.name())
//                                                .distributing(
//                                                        solventPayer.name(),
//                                                        firstReceiver.name(),
//                                                        secondReceiver.name()))
//                                        .fee(ONE_HBAR / 10))
//                        .designatingPayer(solventPayer.name())
//                        .alsoSigningWith(solventPayer.name())
//                        .via("committed"),
//                getTxnRecord("committed").scheduled().hasPriority(recordWith().status(SUCCESS)),
//                // Immediate trigger a schedule dispatch that rolls back as payer cannot afford two auto-associations
//                scheduleCreate(
//                                "rolledBackTxn",
//                                cryptoTransfer(moving(2, secondToken.name())
//                                                .distributing(
//                                                        insolventPayer.name(),
//                                                        firstReceiver.name(),
//                                                        secondReceiver.name()))
//                                        .fee(ONE_HBAR / 10))
//                        .designatingPayer(insolventPayer.name())
//                        .alsoSigningWith(insolventPayer.name())
//                        .via("rolledBack"),
//                getTxnRecord("rolledBack").scheduled().hasPriority(recordWith().status(INSUFFICIENT_PAYER_BALANCE)));
//    }
// }
