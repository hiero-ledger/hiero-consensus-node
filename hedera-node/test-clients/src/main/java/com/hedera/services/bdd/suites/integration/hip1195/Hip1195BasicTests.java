// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration.hip1195;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewContract;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.GLOBAL_WATCHER;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.verification.traceability.SidecarWatcher;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(13)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(CONCURRENT)
public class Hip1195BasicTests {

    @Contract(contract = "FalsePreHook", creationGas = 5_000_000)
    static SpecContract FALSE_ALLOWANCE_HOOK;

    @Contract(contract = "TruePreHook", creationGas = 5_000_000)
    static SpecContract TRUE_ALLOWANCE_HOOK;

    @Contract(contract = "TruePrePostHook", creationGas = 5_000_000)
    static SpecContract TRUE_PRE_POST_ALLOWANCE_HOOK;

    @Contract(contract = "FalsePrePostHook", creationGas = 5_000_000)
    static SpecContract FALSE_PRE_POST_ALLOWANCE_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(FALSE_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_PRE_POST_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(FALSE_PRE_POST_ALLOWANCE_HOOK.getInfo());

        testLifecycle.doAdhoc(withOpContext(
                (spec, opLog) -> GLOBAL_WATCHER.set(new SidecarWatcher(spec.recordStreamsLoc(byNodeId(0))))));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoCreateAccountWithHookCreationDetails() {
        return hapiTest(
                cryptoCreate("accountWithHook").withHooks(accountAllowanceHook(200L, TRUE_ALLOWANCE_HOOK.name())),
                viewAccount("accountWithHook", (Account a) -> {
                    assertEquals(200L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoUpdateAccountWithoutHooksWithHookCreationDetails() {
        return hapiTest(
                cryptoCreate("accountWithoutHooks"),
                viewAccount("accountWithoutHooks", (Account a) -> {
                    assertEquals(0L, a.firstHookId());
                    assertEquals(0, a.numberHooksInUse());
                }),
                cryptoUpdate("accountWithoutHooks").withHooks(accountAllowanceHook(201L, TRUE_ALLOWANCE_HOOK.name())),
                viewAccount("accountWithoutHooks", (Account a) -> {
                    assertEquals(201L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> createMultipleHooksInSingleCryptoCreate() {
        return hapiTest(
                cryptoCreate("accountWithMultipleHooks")
                        .withHooks(
                                accountAllowanceHook(202L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(203L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(204L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                viewAccount("accountWithMultipleHooks", (Account a) -> {
                    assertEquals(202L, a.firstHookId());
                    assertEquals(3, a.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> createMultipleHooksInSingleCryptoUpdate() {
        return hapiTest(
                cryptoCreate("accountForUpdate"),
                cryptoUpdate("accountForUpdate")
                        .withHooks(
                                accountAllowanceHook(205L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(206L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(207L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                viewAccount("accountForUpdate", (Account a) -> {
                    assertEquals(205L, a.firstHookId());
                    assertEquals(3, a.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoCreateWithMultipleHooksAndCryptoUpdateToAddMoreHooks() {
        return hapiTest(
                cryptoCreate("accountWithTwoHooks")
                        .withHooks(
                                accountAllowanceHook(210L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(211L, FALSE_ALLOWANCE_HOOK.name())),
                viewAccount("accountWithTwoHooks", (Account a) -> {
                    assertEquals(210L, a.firstHookId());
                    assertEquals(2, a.numberHooksInUse());
                }),
                cryptoUpdate("accountWithTwoHooks")
                        .withHooks(
                                accountAllowanceHook(212L, TRUE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(213L, FALSE_PRE_POST_ALLOWANCE_HOOK.name())),
                viewAccount("accountWithTwoHooks", (Account a) -> {
                    assertEquals(212L, a.firstHookId());
                    assertEquals(4, a.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoCreateTwoAccountsWithSameHookId() {
        return hapiTest(
                cryptoCreate("account1").withHooks(accountAllowanceHook(215L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate("account2").withHooks(accountAllowanceHook(215L, TRUE_ALLOWANCE_HOOK.name())),
                viewAccount("account1", (Account a) -> {
                    assertEquals(215L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }),
                viewAccount("account2", (Account a) -> {
                    assertEquals(215L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoCreateWithHookAndCryptoUpdateWithEditedHookFails() {
        return hapiTest(
                cryptoCreate("accountToEdit").withHooks(accountAllowanceHook(216L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoUpdate("accountToEdit")
                        .withHooks(accountAllowanceHook(216L, FALSE_ALLOWANCE_HOOK.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoCreateWithDuplicateHookIdsFails() {
        return hapiTest(cryptoCreate("accountWithDuplicates")
                .withHooks(
                        accountAllowanceHook(217L, TRUE_ALLOWANCE_HOOK.name()),
                        accountAllowanceHook(217L, FALSE_ALLOWANCE_HOOK.name()))
                .hasPrecheck(HOOK_ID_REPEATED_IN_CREATION_DETAILS));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoUpdateWithDuplicateHookIdsFails() {
        return hapiTest(
                cryptoCreate("accountForDuplicateUpdate"),
                cryptoUpdate("accountForDuplicateUpdate")
                        .withHooks(
                                accountAllowanceHook(218L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(218L, FALSE_ALLOWANCE_HOOK.name()))
                        .hasPrecheck(HOOK_ID_REPEATED_IN_CREATION_DETAILS));
    }

    @HapiTest
    final Stream<DynamicTest> deleteThenCreateSameHookIdInSingleUpdate() {
        return hapiTest(
                cryptoCreate("accountForRecreate").withHooks(accountAllowanceHook(223L, TRUE_ALLOWANCE_HOOK.name())),
                viewAccount("accountForRecreate", (Account a) -> {
                    assertEquals(223L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }),
                cryptoUpdate("accountForRecreate")
                        .removingHooks(223L)
                        .withHooks(accountAllowanceHook(223L, FALSE_ALLOWANCE_HOOK.name())),
                viewAccount("accountForRecreate", (Account a) -> {
                    assertEquals(223L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> preHookWithAllowanceSuccessfulCryptoTransfer() {
        return hapiTest(
                cryptoCreate("payer"),
                cryptoCreate("senderWithHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(224L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR).between("senderWithHook", "receiverAccount"))
                        .withPreHookFor("senderWithHook", 224L, 25_000L, "")
                        .payingWith("payer"),
                getAccountBalance("receiverAccount").hasTinyBars(ONE_HUNDRED_HBARS + (10 * ONE_HBAR)));
    }

    @HapiTest
    final Stream<DynamicTest> preHookWithoutAllowanceCryptoTransferFails() {
        return hapiTest(
                cryptoCreate("senderWithHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(225L, FALSE_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(ONE_HUNDRED_HBARS),
                // Transfer should fail because there's no allowance approved
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR).between("senderWithHook", "receiverAccount"))
                        .withPreHookFor("senderWithHook", 225L, 25_000L, "")
                        .payingWith("receiverAccount")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @HapiTest
    final Stream<DynamicTest> preHookExceedsLimitCryptoTransferFails() {
        return hapiTest(
                cryptoCreate("senderWithFalseHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(226L, FALSE_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR)
                                .between("senderWithFalseHook", "receiverAccount"))
                        .withPreHookFor("senderWithFalseHook", 226L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @HapiTest
    final Stream<DynamicTest> prePostHookWithAllowanceSuccessfulCryptoTransfer() {
        return hapiTest(
                cryptoCreate("senderWithPrePostHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(227L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR)
                                .between("senderWithPrePostHook", "receiverAccount"))
                        .withPrePostHookFor("senderWithPrePostHook", 227L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER),
                getAccountBalance("receiverAccount").hasTinyBars(10 * ONE_HBAR));
    }

    @HapiTest
    final Stream<DynamicTest> prePostHookWithoutAllowanceCryptoTransferFails() {
        return hapiTest(
                cryptoCreate("senderWithPrePostHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(228L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR)
                                .between("senderWithPrePostHook", "receiverAccount"))
                        .withPrePostHookFor("senderWithPrePostHook", 228L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> prePostHookExceedsLimitCryptoTransferFails() {
        return hapiTest(
                cryptoCreate("senderWithFalsePrePostHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(229L, FALSE_PRE_POST_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR)
                                .between("senderWithFalsePrePostHook", "receiverAccount"))
                        .withPrePostHookFor("senderWithFalsePrePostHook", 229L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @HapiTest
    final Stream<DynamicTest> prePostHookPreReturnsFalseTransferFails() {
        return hapiTest(
                cryptoCreate("senderWithFalsePreHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(230L, FALSE_PRE_POST_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR)
                                .between("senderWithFalsePreHook", "receiverAccount"))
                        .withPrePostHookFor("senderWithFalsePreHook", 230L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @HapiTest
    final Stream<DynamicTest> prePostHookBothReturnTrueTransferSuccessful() {
        return hapiTest(
                cryptoCreate("senderWithTruePrePostHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(231L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR)
                                .between("senderWithTruePrePostHook", "receiverAccount"))
                        .withPrePostHookFor("senderWithTruePrePostHook", 231L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER),
                getAccountBalance("receiverAccount").hasTinyBars(10 * ONE_HBAR));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferReferencingNonExistentHookIdFails() {
        return hapiTest(
                cryptoCreate("senderAccount").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR).between("senderAccount", "receiverAccount"))
                        .withPreHookFor("senderAccount", 999L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("txWithNonExistentHook"),
                getTxnRecord("txWithNonExistentHook")
                        .andAllChildRecords()
                        .hasChildRecords(TransactionRecordAsserts.recordWith().status(HOOK_NOT_FOUND)));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferTokenWithCustomFeesHookChecksAndFails() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                cryptoCreate("feeCollector"),
                cryptoCreate("senderWithHook").withHooks(accountAllowanceHook(232L, FALSE_ALLOWANCE_HOOK.name())),
                tokenCreate("tokenWithFees")
                        .treasury("treasury")
                        .supplyKey("supplyKey")
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(1L, "feeCollector")),
                tokenAssociate("senderWithHook", "tokenWithFees"),
                cryptoTransfer(TokenMovement.moving(100, "tokenWithFees").between("treasury", "senderWithHook")),
                cryptoTransfer(TokenMovement.moving(10, "tokenWithFees").between("senderWithHook", "treasury"))
                        .withPreHookFor("senderWithHook", 232L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferTokenWithoutCustomFeesHookChecksAndSucceeds() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                cryptoCreate("senderWithHook").withHooks(accountAllowanceHook(233L, TRUE_ALLOWANCE_HOOK.name())),
                tokenCreate("tokenWithoutFees")
                        .treasury("treasury")
                        .supplyKey("supplyKey")
                        .initialSupply(1000L),
                tokenAssociate("senderWithHook", "tokenWithoutFees"),
                cryptoTransfer(TokenMovement.moving(100, "tokenWithoutFees").between("treasury", "senderWithHook")),
                cryptoTransfer(TokenMovement.moving(10, "tokenWithoutFees").between("senderWithHook", "treasury"))
                        .withPreHookFor("senderWithHook", 233L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> nftTransferNotSignedByReceiverWithReceiverSigRequiredAndHookReturnsTrue() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                newKeyNamed("receiverKey"),
                cryptoCreate("treasury"),
                cryptoCreate("sender"),
                cryptoCreate("receiverWithHook")
                        .receiverSigRequired(true)
                        .key("receiverKey")
                        .withHooks(accountAllowanceHook(240L, TRUE_ALLOWANCE_HOOK.name())),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury("treasury")
                        .supplyKey("supplyKey")
                        .initialSupply(0L),
                tokenAssociate("sender", "nft"),
                tokenAssociate("receiverWithHook", "nft"),
                mintToken("nft", List.of(ByteString.copyFromUtf8("metadata1"))),
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("treasury", "sender")),
                // Transfer without receiver signature but with hook that returns true
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("sender", "receiverWithHook"))
                        .withNftReceiverPreHookFor("receiverWithHook", 240L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER, "sender"));
    }

    @HapiTest
    final Stream<DynamicTest> nftTransferNotSignedByReceiverWithReceiverSigRequiredAndHookReturnsFalse() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                newKeyNamed("receiverKey"),
                cryptoCreate("treasury"),
                cryptoCreate("sender"),
                cryptoCreate("receiverWithFalseHook")
                        .receiverSigRequired(true)
                        .key("receiverKey")
                        .withHooks(accountAllowanceHook(241L, FALSE_ALLOWANCE_HOOK.name())),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury("treasury")
                        .supplyKey("supplyKey")
                        .initialSupply(0L),
                tokenAssociate("sender", "nft"),
                tokenAssociate("receiverWithFalseHook", "nft"),
                mintToken("nft", List.of(ByteString.copyFromUtf8("metadata1"))),
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("treasury", "sender")),
                // Transfer without receiver signature and hook returns false
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("sender", "receiverWithFalseHook"))
                        .withNftReceiverPreHookFor("receiverWithFalseHook", 241L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER, "sender")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @HapiTest
    final Stream<DynamicTest> nftTransferSignedByReceiverWithoutReceiverSigRequiredAndHookReturnsTrue() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                cryptoCreate("sender"),
                cryptoCreate("receiverWithHook")
                        .receiverSigRequired(false)
                        .withHooks(accountAllowanceHook(242L, TRUE_ALLOWANCE_HOOK.name())),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury("treasury")
                        .supplyKey("supplyKey")
                        .initialSupply(0L),
                tokenAssociate("sender", "nft"),
                tokenAssociate("receiverWithHook", "nft"),
                mintToken("nft", List.of(ByteString.copyFromUtf8("metadata1"))),
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("treasury", "sender")),
                // Transfer with receiver signature even though not required
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("sender", "receiverWithHook"))
                        .withNftReceiverPreHookFor("receiverWithHook", 242L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER, "sender", "receiverWithHook"));
    }

    @HapiTest
    final Stream<DynamicTest> nftTransferNotSignedByReceiverWithoutReceiverSigRequiredAndHookReturnsTrue() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                cryptoCreate("sender"),
                cryptoCreate("receiverWithHook")
                        .receiverSigRequired(false)
                        .withHooks(accountAllowanceHook(243L, TRUE_ALLOWANCE_HOOK.name())),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury("treasury")
                        .supplyKey("supplyKey")
                        .initialSupply(0L),
                tokenAssociate("sender", "nft"),
                tokenAssociate("receiverWithHook", "nft"),
                mintToken("nft", List.of(ByteString.copyFromUtf8("metadata1"))),
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("treasury", "sender")),
                // Transfer without receiver signature and hook returns true
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("sender", "receiverWithHook"))
                        .withNftReceiverPreHookFor("receiverWithHook", 243L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER, "sender"));
    }

    @HapiTest
    final Stream<DynamicTest> nftTransferNotSignedByReceiverWithoutReceiverSigRequiredAndHookReturnsFalse() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                cryptoCreate("sender"),
                cryptoCreate("receiverWithFalseHook")
                        .receiverSigRequired(false)
                        .withHooks(accountAllowanceHook(244L, FALSE_ALLOWANCE_HOOK.name())),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury("treasury")
                        .supplyKey("supplyKey")
                        .initialSupply(0L),
                tokenAssociate("sender", "nft"),
                tokenAssociate("receiverWithFalseHook", "nft"),
                mintToken("nft", List.of(ByteString.copyFromUtf8("metadata1"))),
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("treasury", "sender")),
                // Transfer without receiver signature and hook returns false
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("sender", "receiverWithFalseHook"))
                        .withNftReceiverPreHookFor("receiverWithFalseHook", 244L, 25_000L, "")
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER, "sender")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    // ================================================================================================================
    // CONTRACT HOOK CREATION AND REFERENCE TEST CASES
    // ================================================================================================================

    @HapiTest
    final Stream<DynamicTest> contractCreateWithHookCreationDetails() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate").withHooks(accountAllowanceHook(300L, TRUE_ALLOWANCE_HOOK.name())),
                viewContract("SimpleUpdate", (c) -> assertEquals(1, c.numberHooksInUse())));
    }

    @HapiTest
    final Stream<DynamicTest> contractUpdateWithoutHooksWithHookCreationDetails() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate"),
                viewContract("SimpleUpdate", (c) -> assertEquals(0, c.numberHooksInUse())),
                contractUpdate("SimpleUpdate").withHooks(accountAllowanceHook(301L, TRUE_ALLOWANCE_HOOK.name())),
                viewContract("SimpleUpdate", (c) -> assertEquals(1, c.numberHooksInUse())));
    }

    @HapiTest
    final Stream<DynamicTest> createMultipleHooksInSingleContractCreate() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate")
                        .withHooks(
                                accountAllowanceHook(302L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(303L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(304L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                viewContract("SimpleUpdate", (c) -> assertEquals(3, c.numberHooksInUse())));
    }

    @HapiTest
    final Stream<DynamicTest> createMultipleHooksInSingleContractUpdate() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate"),
                contractUpdate("SimpleUpdate")
                        .withHooks(
                                accountAllowanceHook(305L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(306L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(307L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                viewContract("SimpleUpdate", (c) -> assertEquals(3, c.numberHooksInUse())));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateWithMultipleHooksAndContractUpdateToAddMoreHooks() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate")
                        .withHooks(
                                accountAllowanceHook(310L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(311L, FALSE_ALLOWANCE_HOOK.name())),
                viewContract("SimpleUpdate", (c) -> assertEquals(2, c.numberHooksInUse())),
                contractUpdate("SimpleUpdate")
                        .withHooks(
                                accountAllowanceHook(312L, TRUE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(313L, FALSE_PRE_POST_ALLOWANCE_HOOK.name())),
                viewContract("SimpleUpdate", (c) -> assertEquals(4, c.numberHooksInUse())));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateTwoContractsWithSameHookId() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate").withHooks(accountAllowanceHook(314L, TRUE_ALLOWANCE_HOOK.name())),
                contractCreate("SimpleUpdate").withHooks(accountAllowanceHook(314L, TRUE_ALLOWANCE_HOOK.name())));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateWithHookAndContractUpdateWithEditedHookFails() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate").withHooks(accountAllowanceHook(315L, TRUE_ALLOWANCE_HOOK.name())),
                contractUpdate("SimpleUpdate")
                        .withHooks(accountAllowanceHook(315L, FALSE_ALLOWANCE_HOOK.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateWithDuplicateHookIdsFails() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate")
                        .withHooks(
                                accountAllowanceHook(316L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(316L, FALSE_ALLOWANCE_HOOK.name()))
                        .hasPrecheck(HOOK_ID_REPEATED_IN_CREATION_DETAILS));
    }

    @HapiTest
    final Stream<DynamicTest> contractUpdateWithDuplicateHookIdsFails() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate"),
                contractUpdate("SimpleUpdate")
                        .withHooks(
                                accountAllowanceHook(317L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(317L, FALSE_ALLOWANCE_HOOK.name()))
                        .hasPrecheck(HOOK_ID_REPEATED_IN_CREATION_DETAILS));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateWithAlreadyCreatedHookIdFails() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate").withHooks(accountAllowanceHook(318L, TRUE_ALLOWANCE_HOOK.name())),
                contractUpdate("SimpleUpdate")
                        .withHooks(accountAllowanceHook(318L, FALSE_ALLOWANCE_HOOK.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE));
    }

    @HapiTest
    final Stream<DynamicTest> contractUpdateWithAlreadyCreatedHookIdFails() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                contractCreate("SimpleUpdate").withHooks(accountAllowanceHook(319L, TRUE_ALLOWANCE_HOOK.name())),
                contractUpdate("SimpleUpdate")
                        .withHooks(accountAllowanceHook(319L, FALSE_ALLOWANCE_HOOK.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE));
    }

    @HapiTest
    final Stream<DynamicTest> deleteAllHooks() {
        final var OWNER = "acctHeadRun";
        final long A = 1L, B = 2L, C = 3L, D = 4L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(
                                accountAllowanceHook(A, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(B, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(C, FALSE_ALLOWANCE_HOOK.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(A, a.firstHookId());
                    assertEquals(3, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER).removingHooks(A, B, C),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(0L, a.firstHookId());
                    assertEquals(0, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER)
                        .removingHooks(A)
                        .withHooks(accountAllowanceHook(A, FALSE_ALLOWANCE_HOOK.name()))
                        .hasKnownStatus(HOOK_NOT_FOUND),
                cryptoUpdate(OWNER).withHooks(accountAllowanceHook(D, FALSE_ALLOWANCE_HOOK.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(4L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }));
    }
}
