// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration.hip1195;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_STATE_ID;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewContract;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.GLOBAL_WATCHER;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.integration.hip1195.Hip1195EnabledTest.OWNER;
import static com.hedera.services.bdd.suites.integration.hip1195.Hip1195EnabledTest.PAYER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOKS_EXECUTIONS_REQUIRE_TOP_LEVEL_CRYPTO_TRANSFER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_HOOK_CREATION_SPEC;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_HOOKS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Single;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.hooks.EvmHook;
import com.hedera.hapi.node.hooks.EvmHookSpec;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.hooks.HookExtensionPoint;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import com.hedera.services.bdd.spec.verification.traceability.SidecarWatcher;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TransactionRecord;
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
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final double HOOK_INVOCATION_USD = 0.005;
    private static final long HOOK_GAS_LIMIT = 25000;
    private static final double HBAR_TRANSFER_BASE_USD = 0.0001;
    private static final double NFT_TRANSFER_BASE_USD = 0.001;
    private static final double NFT_TRANSFER_WITH_CUSTOM_BASE_USD = 0.002;

    @Contract(contract = "FalsePreHook", creationGas = 5_000_000)
    static SpecContract FALSE_ALLOWANCE_HOOK;

    @Contract(contract = "TruePreHook", creationGas = 5_000_000)
    static SpecContract TRUE_ALLOWANCE_HOOK;

    @Contract(contract = "TruePrePostHook", creationGas = 5_000_000)
    static SpecContract TRUE_PRE_POST_ALLOWANCE_HOOK;

    @Contract(contract = "FalsePrePostHook", creationGas = 5_000_000)
    static SpecContract FALSE_PRE_POST_ALLOWANCE_HOOK;

    @Contract(contract = "FalseTruePrePostHook", creationGas = 5_000_000)
    static SpecContract FALSE_TRUE_ALLOWANCE_HOOK;

    @Contract(contract = "SelfDestructOpHook", creationGas = 5_000_000)
    static SpecContract SELF_DESTRUCT_HOOK;

    @Contract(contract = "EmitSenderOrigin", creationGas = 500_000)
    static SpecContract EMIT_SENDER_ORIGIN;

    @Contract(contract = "AddressLogsHook", creationGas = 1_000_000)
    static SpecContract ADDRESS_LOGS_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(FALSE_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_PRE_POST_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(FALSE_PRE_POST_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(SELF_DESTRUCT_HOOK.getInfo());
        testLifecycle.doAdhoc(FALSE_TRUE_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(EMIT_SENDER_ORIGIN.getInfo());
        testLifecycle.doAdhoc(ADDRESS_LOGS_HOOK.getInfo());

        testLifecycle.doAdhoc(withOpContext(
                (spec, opLog) -> GLOBAL_WATCHER.set(new SidecarWatcher(spec.recordStreamsLoc(byNodeId(0))))));
    }

    @HapiTest
    final Stream<DynamicTest> msgSenderUsesHookOwnerEvmAddress() {
        final var addressTuple = TupleType.parse("(address)");
        return hapiTest(
                newKeyNamed("payerEcKey").shape(SECP256K1_ON),
                newKeyNamed("ownerEcKey").shape(SECP256K1_ON),
                cryptoCreate("payer")
                        .balance(ONE_MILLION_HBARS)
                        .key("payerEcKey")
                        .withMatchingEvmAddress(),
                cryptoCreate("owner")
                        .key("ownerEcKey")
                        .withMatchingEvmAddress()
                        // This hook calls a EmitSenderOrigin.logNow() method to emit origin + sender addresses
                        .withHooks(accountAllowanceHook(42L, ADDRESS_LOGS_HOOK.name())),
                sourcingContextual(spec -> {
                    final var target = EMIT_SENDER_ORIGIN.addressOn(spec.targetNetworkOrThrow());
                    final var calldata = addressTuple.encode(Single.of(target));
                    return cryptoTransfer(movingHbar(ONE_HBAR).between("owner", FUNDING))
                            .withPreHookFor("owner", 42L, 250_000L, calldata)
                            .payingWith("payer")
                            .signedBy("payer")
                            .via("txn");
                }),
                withAddressOfKey(
                        "payerEcKey",
                        payerAddress -> withAddressOfKey("ownerEcKey", ownerAddress -> getTxnRecord("txn")
                                .andAllChildRecords()
                                .exposingAllTo(records -> {
                                    // We find the EmitSenderOrigin log event and extract the addresses for validation
                                    final var hookExecutionRecord = records.stream()
                                            .filter(TransactionRecord::hasContractCallResult)
                                            .findAny()
                                            .orElseThrow();
                                    final var addressLog = hookExecutionRecord
                                            .getContractCallResult()
                                            .getLogInfo(0);
                                    final var twoAddresses = TupleType.parse("(address,address)");
                                    final var decoded = twoAddresses.decode(
                                            addressLog.getData().toByteArray());
                                    final var origin = (Address) decoded.get(0);
                                    final var sender = (Address) decoded.get(1);
                                    assertEquals(payerAddress, origin, "Origin address is not the same as the payer");
                                    assertEquals(ownerAddress, sender, "Sender address is not the same as the owner");
                                }))));
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
                        .withPreHookFor("senderWithHook", 224L, HOOK_GAS_LIMIT, "")
                        .payingWith("payer")
                        .via("transferTxn"),
                getAccountBalance("receiverAccount").hasTinyBars(ONE_HUNDRED_HBARS + (10 * ONE_HBAR)),
                getTxnRecord("transferTxn").andAllChildRecords().exposingAllTo(txRecords -> {
                    final var callTxRecord = txRecords.stream()
                            .filter(TransactionRecord::hasContractCallResult)
                            .findFirst();
                    assertTrue(callTxRecord.isPresent());
                    final var logs = callTxRecord.get().getContractCallResult().getLogInfoList();
                    assertEquals(1, logs.size());
                }));
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
                        .withPreHookFor("senderWithHook", 225L, HOOK_GAS_LIMIT, "")
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
                        .withPreHookFor("senderWithFalseHook", 226L, HOOK_GAS_LIMIT, "")
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
                        .withPrePostHookFor("senderWithPrePostHook", 227L, HOOK_GAS_LIMIT, "")
                        .payingWith(DEFAULT_PAYER),
                getAccountBalance("receiverAccount").hasTinyBars(10 * ONE_HBAR));
    }

    @HapiTest
    final Stream<DynamicTest> prePostHookWithoutAllowanceCryptoTransferFails() {
        return hapiTest(
                cryptoCreate("senderWithPrePostHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(228L, FALSE_PRE_POST_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR)
                                .between("senderWithPrePostHook", "receiverAccount"))
                        .withPrePostHookFor("senderWithPrePostHook", 228L, HOOK_GAS_LIMIT, "")
                        .payingWith(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @HapiTest
    final Stream<DynamicTest> falsePreTruePostCryptoTransferFails() {
        return hapiTest(
                cryptoCreate("senderWithPrePostHook")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(228L, FALSE_TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR)
                                .between("senderWithPrePostHook", "receiverAccount"))
                        .withPrePostHookFor("senderWithPrePostHook", 228L, HOOK_GAS_LIMIT, "")
                        .payingWith(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoCreateWithHooksFailureScenarios() {
        return hapiTest(
                // 1) CryptoCreate with HookCreationDetails fails (EVM hook present but spec missing)
                cryptoCreate("ccFail_missingSpec")
                        .withHook(spec -> HookCreationDetails.newBuilder()
                                .hookId(500L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder())
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 4) CryptoCreate with invalid hook contract_id (spec present but no contract_id set)
                cryptoCreate("ccFail_invalidContractId")
                        .withHook(spec -> HookCreationDetails.newBuilder()
                                .hookId(501L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder()
                                        .spec(EvmHookSpec.newBuilder().build()))
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 5) CryptoCreate without hook bytecode (no EVM hook set)
                cryptoCreate("ccFail_noLambda")
                        .withHook(spec -> HookCreationDetails.newBuilder()
                                .hookId(502L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),

                // Prepare accounts for update scenarios
                cryptoCreate("acctNoHooks"),
                cryptoCreate("acctWithHook").withHooks(accountAllowanceHook(503L, TRUE_ALLOWANCE_HOOK.name())),

                // 2) CryptoUpdate with HookCreationDetails for account without hooks fails (missing spec)
                cryptoUpdate("acctNoHooks")
                        .withHook(spec -> HookCreationDetails.newBuilder()
                                .hookId(504L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder())
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 6) CryptoUpdate with invalid hook contract_id (spec present but no contract_id set)
                cryptoUpdate("acctNoHooks")
                        .withHook(spec -> HookCreationDetails.newBuilder()
                                .hookId(505L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder()
                                        .spec(EvmHookSpec.newBuilder().build()))
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 3) CryptoUpdate with HookCreationDetails for account with hooks fails (missing spec)
                cryptoUpdate("acctWithHook")
                        .withHook(spec -> HookCreationDetails.newBuilder()
                                .hookId(506L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder())
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 7) CryptoUpdate without hook bytecode (no hook set)
                cryptoUpdate("acctWithHook")
                        .withHook(spec -> HookCreationDetails.newBuilder()
                                .hookId(507L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateAndUpdateWithHooksFailureScenarios() {
        return hapiTest(
                uploadInitCode("SimpleUpdate"),
                uploadInitCode("CreateTrivial"),

                // 1) ContractCreate with HookCreationDetails fails (EVM hook present but spec missing)
                contractCreate("SimpleUpdate")
                        .withHooks(spec -> HookCreationDetails.newBuilder()
                                .hookId(520L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder())
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 4) ContractCreate with invalid hook contract_id (spec present but no contract_id set)
                contractCreate("CreateTrivial")
                        .withHooks(spec -> HookCreationDetails.newBuilder()
                                .hookId(521L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder()
                                        .spec(EvmHookSpec.newBuilder().build()))
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 5) ContractCreate without hook bytecode (no EVM hook set)
                contractCreate("SimpleUpdate")
                        .withHooks(spec -> HookCreationDetails.newBuilder()
                                .hookId(522L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),

                // Prepare contracts for update scenarios
                contractCreate("SimpleUpdate").withHooks(accountAllowanceHook(523L, TRUE_ALLOWANCE_HOOK.name())),
                contractCreate("CreateTrivial"),

                // 2) ContractUpdate with HookCreationDetails for contract without hooks fails (missing spec)
                contractUpdate("CreateTrivial")
                        .withHooks(spec -> HookCreationDetails.newBuilder()
                                .hookId(524L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder())
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 6) ContractUpdate with invalid hook contract_id (spec present but no contract_id set)
                contractUpdate("CreateTrivial")
                        .withHooks(spec -> HookCreationDetails.newBuilder()
                                .hookId(525L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder()
                                        .spec(EvmHookSpec.newBuilder().build()))
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 3) ContractUpdate with HookCreationDetails for contract with hooks fails (missing spec)
                contractUpdate("SimpleUpdate")
                        .withHooks(spec -> HookCreationDetails.newBuilder()
                                .hookId(526L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .evmHook(EvmHook.newBuilder())
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC),
                // 7) ContractUpdate without hook bytecode (no EVM hook set)
                contractUpdate("SimpleUpdate")
                        .withHooks(spec -> HookCreationDetails.newBuilder()
                                .hookId(527L)
                                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                                .build())
                        .hasKnownStatus(INVALID_HOOK_CREATION_SPEC));
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
                        .withPrePostHookFor("senderWithFalsePreHook", 230L, HOOK_GAS_LIMIT, "")
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
                        .withPrePostHookFor("senderWithTruePrePostHook", 231L, HOOK_GAS_LIMIT, "")
                        .payingWith(DEFAULT_PAYER),
                getAccountBalance("receiverAccount").hasTinyBars(10 * ONE_HBAR));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferReferencingNonExistentHookIdFails() {
        return hapiTest(
                cryptoCreate("senderAccount").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("receiverAccount").balance(0L),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR).between("senderAccount", "receiverAccount"))
                        .withPreHookFor("senderAccount", 999L, HOOK_GAS_LIMIT, "")
                        .payingWith(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("txWithNonExistentHook"),
                getTxnRecord("txWithNonExistentHook")
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(HOOK_NOT_FOUND)));
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
                        .withPreHookFor("senderWithHook", 232L, HOOK_GAS_LIMIT, "")
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
                        .withPreHookFor("senderWithHook", 233L, HOOK_GAS_LIMIT, "")
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
                        .withNftReceiverPreHookFor("receiverWithHook", 240L, HOOK_GAS_LIMIT, "")
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
                        .withNftReceiverPreHookFor("receiverWithFalseHook", 241L, HOOK_GAS_LIMIT, "")
                        .payingWith("sender")
                        .signedBy("sender")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("nftTransferFails"),
                sourcingContextual(spec -> {
                    final long tinybarGasCost =
                            HOOK_GAS_LIMIT * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd(
                            "nftTransferFails", NFT_TRANSFER_BASE_USD + HOOK_INVOCATION_USD + usdGasCost);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> nftTransferWithCustomFeesAndHooksFees() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                newKeyNamed("receiverKey"),
                cryptoCreate("treasury").withHooks(accountAllowanceHook(241L, FALSE_TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate("feeCollector"),
                cryptoCreate("sender").withHooks(accountAllowanceHook(242L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate("receiver")
                        .receiverSigRequired(true)
                        .key("receiverKey")
                        .withHooks(accountAllowanceHook(243L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury("treasury")
                        .supplyKey("supplyKey")
                        .initialSupply(0L)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(fixedHbarFee(1L, "feeCollector")),
                tokenAssociate("sender", "nft"),
                tokenAssociate("receiver", "nft"),
                mintToken("nft", List.of(ByteString.copyFromUtf8("metadata1"))),
                cryptoTransfer(TokenMovement.movingUnique("nft", 1L).between("treasury", "sender")),
                cryptoTransfer(
                                TokenMovement.movingUnique("nft", 1L).between("sender", "receiver"),
                                TokenMovement.movingHbar(1L).between("sender", "treasury"))
                        .withNftReceiverPrePostHookFor("receiver", 243L, 2 * HOOK_GAS_LIMIT, "")
                        .withNftSenderPreHookFor("sender", 242L, 2 * HOOK_GAS_LIMIT, "")
                        .withPreHookFor("sender", 242L, 3 * HOOK_GAS_LIMIT, "")
                        .withPrePostHookFor("treasury", 241L, 2 * HOOK_GAS_LIMIT, "")
                        .payingWith("sender")
                        .signedBy("sender")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("nftTransferFails"),
                sourcingContextual(spec -> {
                    // There are 4 hooks - 2 pre and 2 pre-post.
                    // 2 pre hooks succeed and third pre hook call fails
                    // so we should refund the gas and hook invocation cost of other treasury post hook = 2 *
                    // HOOK_GAS_LIMIT
                    //  and also the receiver pre-post hook = 2 (2 * HOOK_GAS_LIMIT)
                    final long tinybarGasCost =
                            (7 * HOOK_GAS_LIMIT) * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd(
                            "nftTransferFails",
                            NFT_TRANSFER_WITH_CUSTOM_BASE_USD + (3 * HOOK_INVOCATION_USD) + usdGasCost);
                }));
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
                        .withNftReceiverPreHookFor("receiverWithHook", 242L, HOOK_GAS_LIMIT, "")
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
                        .withNftReceiverPreHookFor("receiverWithHook", 243L, HOOK_GAS_LIMIT, "")
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
                        .withNftReceiverPreHookFor("receiverWithFalseHook", 244L, HOOK_GAS_LIMIT, "")
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER, "sender")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK));
    }

    @HapiTest
    final Stream<DynamicTest> mixedAppearancesRequireSigIfNotAllHookAuthorized() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("treasury"),
                cryptoCreate("senderWithHook").withHooks(accountAllowanceHook(260L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate("rcvFungible"),
                cryptoCreate("rcvNft"),

                // Create FT and NFT and distribute to sender
                tokenCreate("ft").treasury("treasury").supplyKey("supplyKey").initialSupply(1_000L),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury("treasury")
                        .supplyKey("supplyKey")
                        .initialSupply(0L),
                tokenAssociate("senderWithHook", List.of("ft", "nft")),
                tokenAssociate("rcvFungible", "ft"),
                tokenAssociate("rcvNft", "nft"),
                mintToken("nft", List.of(ByteString.copyFromUtf8("metadata1"))),
                cryptoTransfer(
                        TokenMovement.moving(100, "ft").between("treasury", "senderWithHook"),
                        TokenMovement.movingUnique("nft", 1L).between("treasury", "senderWithHook")),
                // Now sender appears twice: once as FT sender (authorized by fungible pre-hook) and
                // once as NFT sender (no NFT sender hook provided) => must still sign
                cryptoTransfer(
                                TokenMovement.moving(10, "ft").between("senderWithHook", "rcvFungible"),
                                TokenMovement.movingUnique("nft", 1L).between("senderWithHook", "rcvNft"))
                        .withPreHookFor("senderWithHook", 260L, HOOK_GAS_LIMIT, "")
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE));
    }

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

    @HapiTest
    final Stream<DynamicTest> cryptoCreateWithHookFeesScalesAsExpected() {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("accountWithHook")
                        .withHooks(accountAllowanceHook(400L, TRUE_ALLOWANCE_HOOK.name()))
                        .via("accountWithHookCreation")
                        .balance(ONE_HBAR)
                        .payingWith("payer"),
                // One hook price 1 USD and cryptoCreate price 0.05 USD
                validateChargedUsd("accountWithHookCreation", 1.05),
                cryptoCreate("accountWithMultipleHooks")
                        .withHooks(
                                accountAllowanceHook(401L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(402L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(403L, TRUE_ALLOWANCE_HOOK.name()))
                        .via("accountWithMultipleHooksCreation")
                        .payingWith("payer"),
                validateChargedUsd("accountWithMultipleHooksCreation", 3.05));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoUpdateWithHookFeesScalesAsExpected() {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("accountWithHook")
                        .withHooks(accountAllowanceHook(400L, TRUE_ALLOWANCE_HOOK.name()))
                        .balance(ONE_HBAR)
                        .payingWith("payer"),
                cryptoUpdate("accountWithHook")
                        .removingHook(400L)
                        .withHooks(
                                accountAllowanceHook(401L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(402L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(403L, TRUE_ALLOWANCE_HOOK.name()))
                        .blankMemo()
                        .via("hookUpdates")
                        .payingWith("payer"),
                // hook creations and deletions are 1 USD each, and cryptoUpdate is 0.00022 USD
                validateChargedUsd("hookUpdates", 4.00022));
    }

    @HapiTest
    final Stream<DynamicTest> contractUpdateWithHookFeesScalesAsExpected() {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                uploadInitCode("CreateTrivial"),
                contractCreate("CreateTrivial")
                        .withHooks(accountAllowanceHook(400L, TRUE_ALLOWANCE_HOOK.name()))
                        .gas(5_000_000L)
                        .via("contractWithHookCreation")
                        .payingWith("payer"),
                // One hook price 1 USD and contractCreate price 0.74 USD
                validateChargedUsd("contractWithHookCreation", 1.74),
                contractCreate("CreateTrivial")
                        .withHooks(
                                accountAllowanceHook(400L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(401L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(402L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(403L, TRUE_ALLOWANCE_HOOK.name()))
                        .gas(5_000_000L)
                        .via("contractsWithHookCreation")
                        .payingWith("payer"),
                // One hook price 1 USD and contractCreate price 0.74 USD
                validateChargedUsd("contractsWithHookCreation", 4.74),
                contractUpdate("CreateTrivial")
                        .removingHook(400L)
                        .withHooks(
                                accountAllowanceHook(404L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(405L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(406L, TRUE_ALLOWANCE_HOOK.name()))
                        .blankMemo()
                        .via("hookUpdates")
                        .payingWith("payer"),
                // hook creations and deletions are 1 USD each, and contractUpdate is 0.026 USD
                validateChargedUsd("hookUpdates", 4.026));
    }

    @HapiTest
    final Stream<DynamicTest> hookExecutionFeeScalesWithMultipleHooks() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name()))
                        .balance(ONE_MILLION_HBARS),
                cryptoCreate(PAYER)
                        .receiverSigRequired(true)
                        .withHooks(
                                accountAllowanceHook(123L, TRUE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, HOOK_GAS_LIMIT, "")
                        .payingWith(OWNER)
                        .via("feeTxn"),
                sourcingContextual(spec -> {
                    final long tinybarGasCost =
                            HOOK_GAS_LIMIT * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd("feeTxn", HBAR_TRANSFER_BASE_USD + HOOK_INVOCATION_USD + usdGasCost);
                }),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, PAYER))
                        .withPreHookFor(OWNER, 123L, HOOK_GAS_LIMIT, "")
                        .withPrePostHookFor(PAYER, 123L, HOOK_GAS_LIMIT, "")
                        .payingWith(OWNER)
                        .via("feeTxn2"),
                sourcingContextual(spec -> {
                    // Pre-post hook is called twice, so gas usage is double the given limit
                    final long tinybarGasCost =
                            (3 * HOOK_GAS_LIMIT) * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd(
                            "feeTxn2", HBAR_TRANSFER_BASE_USD + (3 * HOOK_INVOCATION_USD) + usdGasCost);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hookExecutionFeeRefundsOnFailure() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, TRUE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name()))
                        .balance(ONE_MILLION_HBARS),
                cryptoCreate(PAYER)
                        .receiverSigRequired(true)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, PAYER))
                        .withPrePostHookFor(OWNER, 123L, HOOK_GAS_LIMIT, "")
                        .withPrePostHookFor(PAYER, 123L, HOOK_GAS_LIMIT, "")
                        .payingWith(OWNER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("feeTxn"),
                sourcingContextual(spec -> {
                    // There are two pre-post hooks, pre parts are run before and post are run after.
                    // second pre hook fails, so we should refund the gas and hook invocation cost of two calls
                    final long tinybarGasCost =
                            (2 * HOOK_GAS_LIMIT) * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd(
                            "feeTxn", HBAR_TRANSFER_BASE_USD + (2 * HOOK_INVOCATION_USD) + usdGasCost);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> usingWrongContractForPrePostFails() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate(PAYER)
                        .receiverSigRequired(true)
                        .withHooks(
                                accountAllowanceHook(123L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, PAYER))
                        .withPrePostHookFor(PAYER, 123L, HOOK_GAS_LIMIT, "")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .payingWith(OWNER)
                        .via("failedTxn"),
                getTxnRecord("failedTxn")
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(CONTRACT_REVERT_EXECUTED)),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, PAYER))
                        .withPrePostHookFor(PAYER, 124L, HOOK_GAS_LIMIT, "")
                        .payingWith(OWNER));
    }

    @HapiTest
    final Stream<DynamicTest> hooksExecutionsInBatchNotAllowed() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate(PAYER)
                        .receiverSigRequired(true)
                        .withHooks(
                                accountAllowanceHook(123L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                atomicBatch(cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, PAYER))
                                .withPreHookFor(PAYER, 123L, HOOK_GAS_LIMIT, "")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(HOOKS_EXECUTIONS_REQUIRE_TOP_LEVEL_CRYPTO_TRANSFER)
                                .via("transferTxn"))
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> hooksExecutionsInScheduleNotAllowed() {
        return hapiTest(
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate(PAYER)
                        .receiverSigRequired(true)
                        .withHooks(
                                accountAllowanceHook(123L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                scheduleCreate(
                                "schedule",
                                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, PAYER))
                                        .withPreHookFor(PAYER, 123L, HOOK_GAS_LIMIT, ""))
                        .hasPrecheck(HOOKS_EXECUTIONS_REQUIRE_TOP_LEVEL_CRYPTO_TRANSFER)
                        .payingWith(PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> selfDestructOpIsDisabledInHookExecution() {
        return hapiTest(
                cryptoCreate(OWNER).withHooks(accountAllowanceHook(123L, SELF_DESTRUCT_HOOK.name())),
                cryptoCreate(PAYER).receiverSigRequired(true),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, PAYER))
                        .withPreHookFor(OWNER, 123L, 2500_000L, "")
                        .payingWith(PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("failedTxn"),
                getTxnRecord("failedTxn")
                        .andAllChildRecords()
                        .hasChildRecords(
                                recordWith().contractCallResult(resultWith().error("INVALID_OPERATION")))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteAccountWithHooks() {
        return hapiTest(
                cryptoCreate(OWNER).withHooks(accountAllowanceHook(123L, SELF_DESTRUCT_HOOK.name())),
                cryptoDelete(OWNER)
                        .hasKnownStatus(TRANSACTION_REQUIRES_ZERO_HOOKS)
                        .payingWith(OWNER),
                // after removing hook can delete successfully
                cryptoUpdate(OWNER).removingHook(123L),
                cryptoDelete(OWNER).payingWith(OWNER));
    }

    /**
     * Repetitive test to validate the linked list management of hooks is as expected for accounts.
     */
    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> accountLinkedListManagementAsExpected() {
        return hapiTest(
                // First accounts whose lists were constructed purely via cryptoCreate
                cryptoCreate("zeroHooksToStart"),
                assertHookIdList("zeroHooksToStart", List.of()),
                cryptoCreate("oneHookToStartZ").withHooks(accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("oneHookToStartZ", List.of(0L)),
                cryptoCreate("oneHookToStartNZ").withHooks(accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("oneHookToStartNZ", List.of(-1L)),
                cryptoCreate("twoHooksToStartZNZ")
                        .withHooks(
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("twoHooksToStartZNZ", List.of(0L, -1L)),
                cryptoCreate("twoHooksToStartNZZ")
                        .withHooks(
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("twoHooksToStartNZZ", List.of(-1L, 0L)),
                cryptoCreate("threeHooksToStartZNZNZ")
                        .withHooks(
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("threeHooksToStartZNZNZ", List.of(0L, -1L, 1L)),
                cryptoCreate("threeHooksToStartNZZNZ")
                        .withHooks(
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("threeHooksToStartNZZNZ", List.of(-1L, 0L, 1L)),
                cryptoCreate("threeHooksToStartNZNZZ")
                        .withHooks(
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("threeHooksToStartNZNZZ", List.of(-1L, 1L, 0L)),
                // Now accounts with lists manipulated via cryptoUpdate
                cryptoUpdate("zeroHooksToStart").withHook(accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("zeroHooksToStart", List.of(0L)),
                cryptoUpdate("zeroHooksToStart").removingHook(0L),
                assertHookIdList("zeroHooksToStart", List.of()),
                cryptoUpdate("oneHookToStartZ")
                        .removingHook(0L)
                        .withHook(accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("oneHookToStartZ", List.of(0L)),
                cryptoUpdate("oneHookToStartNZ").withHook(accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("oneHookToStartNZ", List.of(0L, -1L)),
                cryptoUpdate("twoHooksToStartZNZ")
                        .removingHook(-1L)
                        .withHook(accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("twoHooksToStartZNZ", List.of(-1L, 0L)),
                cryptoUpdate("twoHooksToStartNZZ").withHook(accountAllowanceHook(-2L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("twoHooksToStartNZZ", List.of(-2L, -1L, 0L)),
                cryptoUpdate("threeHooksToStartZNZNZ").removingHook(-1L),
                assertHookIdList("threeHooksToStartZNZNZ", List.of(0L, 1L)),
                cryptoUpdate("threeHooksToStartNZZNZ").removingHooks(1L, 0L, -1L),
                assertHookIdList("threeHooksToStartNZZNZ", List.of()),
                cryptoUpdate("threeHooksToStartNZNZZ").withHook(accountAllowanceHook(2L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("threeHooksToStartNZNZZ", List.of(2L, -1L, 1L, 0L)));
    }

    /**
     * Repetitive test to validate the linked list management of hooks is as expected for accounts.
     */
    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> contractLinkedListManagementAsExpected() {
        return hapiTest(
                // First accounts whose lists were constructed purely via contractCreate
                createDefaultContract("zeroHooksToStart"),
                assertHookIdList("zeroHooksToStart", List.of()),
                createDefaultContract("oneHookToStartZ")
                        .withHooks(accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("oneHookToStartZ", List.of(0L)),
                createDefaultContract("oneHookToStartNZ")
                        .withHooks(accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("oneHookToStartNZ", List.of(-1L)),
                createDefaultContract("twoHooksToStartZNZ")
                        .withHooks(
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("twoHooksToStartZNZ", List.of(0L, -1L)),
                createDefaultContract("twoHooksToStartNZZ")
                        .withHooks(
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("twoHooksToStartNZZ", List.of(-1L, 0L)),
                createDefaultContract("threeHooksToStartZNZNZ")
                        .withHooks(
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("threeHooksToStartZNZNZ", List.of(0L, -1L, 1L)),
                createDefaultContract("threeHooksToStartNZZNZ")
                        .withHooks(
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("threeHooksToStartNZZNZ", List.of(-1L, 0L, 1L)),
                createDefaultContract("threeHooksToStartNZNZZ")
                        .withHooks(
                                accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(1L, TRUE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("threeHooksToStartNZNZZ", List.of(-1L, 1L, 0L)),
                // Now accounts with lists manipulated via cryptoUpdate
                contractUpdate("zeroHooksToStart").withHook(accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("zeroHooksToStart", List.of(0L)),
                contractUpdate("zeroHooksToStart").removingHook(0L),
                assertHookIdList("zeroHooksToStart", List.of()),
                contractUpdate("oneHookToStartZ")
                        .removingHook(0L)
                        .withHook(accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("oneHookToStartZ", List.of(0L)),
                contractUpdate("oneHookToStartNZ").withHook(accountAllowanceHook(0L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("oneHookToStartNZ", List.of(0L, -1L)),
                contractUpdate("twoHooksToStartZNZ")
                        .removingHook(-1L)
                        .withHook(accountAllowanceHook(-1L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("twoHooksToStartZNZ", List.of(-1L, 0L)),
                contractUpdate("twoHooksToStartNZZ").withHook(accountAllowanceHook(-2L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("twoHooksToStartNZZ", List.of(-2L, -1L, 0L)),
                contractUpdate("threeHooksToStartZNZNZ").removingHook(-1L),
                assertHookIdList("threeHooksToStartZNZNZ", List.of(0L, 1L)),
                contractUpdate("threeHooksToStartNZZNZ").removingHooks(1L, 0L, -1L),
                assertHookIdList("threeHooksToStartNZZNZ", List.of()),
                contractUpdate("threeHooksToStartNZNZZ").withHook(accountAllowanceHook(2L, TRUE_ALLOWANCE_HOOK.name())),
                assertHookIdList("threeHooksToStartNZNZZ", List.of(2L, -1L, 1L, 0L)));
    }

    private SpecOperation assertHookIdList(@NonNull final String account, @NonNull final List<Long> expectedHookIds) {
        return blockingOrder(
                viewAccount(account, a -> {
                    if (expectedHookIds.isEmpty()) {
                        assertEquals(0L, a.firstHookId());
                        assertEquals(0, a.numberHooksInUse());
                    } else {
                        assertEquals(expectedHookIds.getFirst(), a.firstHookId());
                        assertEquals(expectedHookIds.size(), a.numberHooksInUse());
                    }
                }),
                sourcingContextual(spec -> {
                    if (expectedHookIds.isEmpty()) {
                        return noOp();
                    }
                    return EmbeddedVerbs.<HookId, EvmHookState>viewKVState(
                            ContractService.NAME, EVM_HOOK_STATES_STATE_ID, state -> {
                                final var hookEntityId = HookEntityId.newBuilder()
                                        .accountId(toPbj(spec.registry().getAccountID(account)))
                                        .build();
                                for (int i = 0, n = expectedHookIds.size(); i < n; i++) {
                                    final var hookId = idWith(hookEntityId, requireNonNull(expectedHookIds.get(i)));
                                    final var evmHookState = state.get(hookId);
                                    assertNotNull(evmHookState, "Missing expected hook state for " + hookId);
                                    // Check prev/next hook IDs
                                    final var actualPrevHookId = evmHookState.previousHookId();
                                    if (i == 0) {
                                        assertNull(
                                                actualPrevHookId,
                                                "Expected no previous hook for initial " + hookId + " for account "
                                                        + account);
                                    } else {
                                        assertEquals(
                                                expectedHookIds.get(i - 1),
                                                actualPrevHookId,
                                                "Wrong previous hook for " + hookId + " for account " + account);
                                    }
                                    final var actualNextHookId = evmHookState.nextHookId();
                                    if (i == n - 1) {
                                        assertNull(
                                                actualNextHookId,
                                                "Expected no next hook for final " + hookId + " for account "
                                                        + account);
                                    } else {
                                        assertEquals(
                                                expectedHookIds.get(i + 1),
                                                actualNextHookId,
                                                "Wrong next hook for " + hookId + " for account " + account);
                                    }
                                }
                            });
                }));
    }

    private static HookId idWith(@NonNull final HookEntityId entityId, final long hookId) {
        return HookId.newBuilder().entityId(entityId).hookId(hookId).build();
    }
}
