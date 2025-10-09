// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration.hip1195;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore.minimalKey;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountLambdaSStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.encodeParametersForConstructor;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.GLOBAL_WATCHER;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractStateChangesSidecarFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.formattedAssertionValue;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_DELETION_REQUIRES_ZERO_STORAGE_SLOTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.unhex;
import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.hooks.LambdaMappingEntry;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.StateChange;
import com.hedera.services.bdd.spec.assertions.StorageChange;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.verification.traceability.SidecarWatcher;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(12)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(CONCURRENT)
public class Hip1195EnabledTest {
    @Contract(contract = "FalseAccountAllowanceHook", creationGas = 5_000_000)
    static SpecContract FALSE_ALLOWANCE_HOOK;

    @Contract(contract = "TrueAccountAllowanceHook", creationGas = 5_000_000)
    static SpecContract TRUE_ALLOWANCE_HOOK;

    @Contract(contract = "TrueAccountAllowancePrePostHook", creationGas = 5_000_000)
    static SpecContract TRUE_PRE_POST_ALLOWANCE_HOOK;

    @Contract(contract = "FalseAccountAllowancePrePostHook", creationGas = 5_000_000)
    static SpecContract FALSE_PRE_POST_ALLOWANCE_HOOK;

    @Contract(contract = "StorageAccessAccountAllowanceHook", creationGas = 5_000_000)
    static SpecContract STORAGE_GET_SLOT_HOOK;

    @Contract(contract = "TransferAccountAllowanceHook", creationGas = 5_000_000)
    static SpecContract TRANSFER_HOOK;

    @Contract(contract = "StorageMappingAllowanceHook", creationGas = 5_000_000)
    static SpecContract STORAGE_GET_MAPPING_HOOK;

    @Contract(contract = "OneTimeCodeTransferAllowance", creationGas = 5_000_000)
    static SpecContract STORAGE_SET_SLOT_HOOK;

    private static final String STRING_ABI =
            "{\"inputs\":[{\"internalType\":\"string\",\"name\":\"_password\",\"type\":\"string\"}],\"stateMutability\":\"nonpayable\",\"type\":\"constructor\"}";

    private static final String OWNER = "owner";
    private static final String HOOK_CONTRACT_NUM = "365";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(FALSE_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_PRE_POST_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(FALSE_PRE_POST_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(STORAGE_GET_SLOT_HOOK.getInfo());
        testLifecycle.doAdhoc(TRANSFER_HOOK.getInfo());
        testLifecycle.doAdhoc(STORAGE_GET_MAPPING_HOOK.getInfo());
        testLifecycle.doAdhoc(STORAGE_SET_SLOT_HOOK.getInfo());

        testLifecycle.doAdhoc(withOpContext(
                (spec, opLog) -> GLOBAL_WATCHER.set(new SidecarWatcher(spec.recordStreamsLoc(byNodeId(0))))));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarDebitPreOnlyHook() {
        return hapiTest(
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 1_000_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarCreditPreOnlyHook() {
        return hapiTest(
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name()))
                        .receiverSigRequired(true),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, OWNER))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, OWNER))
                        .withPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, OWNER))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarDebitPrePostHook() {
        return hapiTest(
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPrePostHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPrePostHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> accessingWrongHookFails() {
        return hapiTest(
                cryptoCreate("accountWithDifferentHooks")
                        .withHooks(accountAllowanceHook(125L, FALSE_ALLOWANCE_HOOK.name())),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPrePostHookFor(OWNER, 125L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(HOOK_NOT_FOUND),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPrePostHookFor("accountWithDifferentHooks", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarCreditPrePostHookReceiverSigRequired() {
        return hapiTest(
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_PRE_POST_ALLOWANCE_HOOK.name()))
                        .receiverSigRequired(true),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, OWNER))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, OWNER))
                        .withPrePostHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, OWNER))
                        .withPrePostHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> storageAccessWorks() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(accountAllowanceHook(124L, STORAGE_GET_SLOT_HOOK.name()))
                        .receiverSigRequired(true),
                // gets rejected because the return value from the allow function is false bye default
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // Change the hook storage's zero slot to 0x01 so that the hook returns true
                accountLambdaSStore("testAccount", 124L)
                        .putSlot(Bytes.EMPTY, Bytes.wrap(new byte[] {(byte) 0x01}))
                        .signedBy(DEFAULT_PAYER, "testAccount"),
                // now the transfer works
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> transferWorksFromOwnerOfTheHook() {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("receiver").balance(0L),
                cryptoCreate("testAccount")
                        .balance(ONE_HUNDRED_HBARS)
                        .withHooks(accountAllowanceHook(124L, TRANSFER_HOOK.name())),
                cryptoTransfer(TokenMovement.movingHbar(10 * ONE_HBAR).between("testAccount", "receiver"))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .payingWith("payer")
                        .signedBy("payer"),
                // even though the hook says msg.sender transfers 10 hbars to receiver,
                // the owner of the hook transfers 1 tinybar in addition to the 10 hbars
                getAccountBalance("testAccount")
                        .hasTinyBars(ONE_HUNDRED_HBARS - 10 * ONE_HBAR - 1)
                        .logged(),
                getAccountBalance("receiver").hasTinyBars(10 * ONE_HBAR).logged());
    }

    @HapiTest
    final Stream<DynamicTest> storageSettingWorks() {
        final var passcode = "open-sesame";
        final var passHash32 = Bytes.wrap(keccak256(org.apache.tuweni.bytes.Bytes.wrap(passcode.getBytes(UTF_8)))
                .toArray());
        final var correctPassword =
                ByteString.copyFrom(encodeParametersForConstructor(new Object[] {passcode}, STRING_ABI));
        final var wrongPassword =
                ByteString.copyFrom(encodeParametersForConstructor(new Object[] {"wrong password"}, STRING_ABI));

        return hapiTest(
                cryptoCreate(OWNER).withHooks(accountAllowanceHook(124L, STORAGE_SET_SLOT_HOOK.name())),
                // gets rejected because the return value from the allow function is false bye default
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // update the required pass code in the hook.
                // Since the contract uses a keccak256 hash of the passcode, we store that in the slot 0
                accountLambdaSStore(OWNER, 124L)
                        .putSlot(Bytes.EMPTY, passHash32)
                        .signedBy(DEFAULT_PAYER, OWNER),
                // since the contract calls abi.decode on the input bytes, we need to pass in the encoded
                // parameters
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, wrongPassword)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // submitting the correct encoded passcode works
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, correctPassword)
                        .signedBy(DEFAULT_PAYER)
                        .via("storageSetTxn"),
                // since it resets the storage slots we should not be able to do another transfer
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, correctPassword)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                expectContractStateChangesSidecarFor(
                        "storageSetTxn",
                        1,
                        List.of(StateChange.stateChangeFor(HOOK_CONTRACT_NUM)
                                .withStorageChanges(StorageChange.readAndWritten(
                                        formattedAssertionValue(0),
                                        formattedAssertionValue(passHash32.toHex()),
                                        formattedAssertionValue(0))))),
                withOpContext(
                        (spec, opLog) -> requireNonNull(GLOBAL_WATCHER.get()).assertExpectations(spec)));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarCreditPreOnlyHookReceiverSigRequired() {
        return hapiTest(
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name()))
                        .receiverSigRequired(true),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, OWNER))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, OWNER))
                        .withPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, OWNER))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenDebitPreOnlyHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                tokenCreate("token")
                        .treasury(OWNER)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(10, "token").between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenWithCustomFeesDebitPreOnlyHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                tokenCreate("token")
                        .treasury(OWNER)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .withCustom(fixedHbarFee(1L, GENESIS))
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(10, "token").between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .via("customFeeTxn"),
                getTxnRecord("customFeeTxn").logged());
    }

    //    @HapiTest
    final Stream<DynamicTest> royaltyAndFractionalTogetherCaseStudy() {
        final var alice = "alice";
        final var amelie = "AMELIE";
        final var usdcTreasury = "bank";
        final var usdcCollector = "usdcFees";
        final var westWindTreasury = "COLLECTION";
        final var westWindArt = "WEST_WIND_ART";
        final var usdc = "USDC";
        final var supplyKey = "SUPPLY";

        final var txnFromTreasury = "TXN_FROM_TREASURY";
        final var txnFromAmelie = "txnFromAmelie";

        return hapiTest(
                newKeyNamed(supplyKey),
                cryptoCreate(alice)
                        .withHooks(accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()))
                        .balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(amelie).withHooks(accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                cryptoCreate(usdcTreasury),
                cryptoCreate(usdcCollector),
                cryptoCreate(westWindTreasury),
                cryptoCreate("receiverUsdc").maxAutomaticTokenAssociations(1),
                tokenCreate(usdc)
                        .signedBy(DEFAULT_PAYER, usdcTreasury, usdcCollector)
                        .initialSupply(Long.MAX_VALUE)
                        .withCustom(fractionalFee(1, 2, 0, OptionalLong.empty(), usdcCollector))
                        .treasury(usdcTreasury),
                tokenAssociate(westWindTreasury, usdc),
                tokenCreate(westWindArt)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(supplyKey)
                        .treasury(westWindTreasury)
                        .withCustom(royaltyFeeWithFallback(
                                1, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindTreasury)),
                tokenAssociate(amelie, List.of(westWindArt, usdc)),
                tokenAssociate(alice, List.of(westWindArt, usdc)),
                mintToken(westWindArt, List.of(copyFromUtf8("test"))),
                cryptoTransfer(moving(200, usdc).between(usdcTreasury, alice)).fee(ONE_HBAR),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(westWindTreasury, amelie))
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(
                                movingUnique(westWindArt, 1L).between(amelie, alice),
                                moving(200, usdc).distributing(alice, amelie, "receiverUsdc"),
                                movingHbar(10 * ONE_HUNDRED_HBARS).between(alice, amelie))
                        .withPreHookFor("AMELIE", 124L, 25_000L, "")
                        .signedBy(amelie, alice)
                        .payingWith(amelie)
                        .via(txnFromAmelie)
                        .fee(ONE_HBAR),
                getTxnRecord(txnFromAmelie).logged());
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenDebitPrePostHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                tokenCreate("token")
                        .treasury(OWNER)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(10, "token").between(OWNER, GENESIS))
                        .withPrePostHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPrePostHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenCreditPrePostHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_PRE_POST_ALLOWANCE_HOOK.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                tokenAssociate(OWNER, "token"),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, OWNER))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, OWNER))
                        .withPrePostHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, OWNER))
                        .withPrePostHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenCreditPreOnlyHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                tokenAssociate(OWNER, "token"),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, OWNER))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, OWNER))
                        .withPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, OWNER))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftDebitSenderPreHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name())),
                tokenCreate("token")
                        .treasury(OWNER)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(0)
                        .maxSupply(1000L),
                mintToken(
                        "token",
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenAssociate(GENESIS, "token"),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(OWNER, GENESIS))
                        .withNftSenderPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(OWNER, GENESIS))
                        .withNftSenderPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftDebitSenderPrePostHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                tokenCreate("token")
                        .treasury(OWNER)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(0)
                        .maxSupply(1000L),
                mintToken(
                        "token",
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenAssociate(GENESIS, "token"),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(OWNER, GENESIS))
                        .withNftSenderPrePostHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(OWNER, GENESIS))
                        .withNftSenderPrePostHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftCreditReceiverPreHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(0)
                        .maxSupply(1000L),
                mintToken(
                        "token",
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenAssociate(OWNER, "token"),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, OWNER))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, OWNER))
                        .withNftSenderPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, OWNER))
                        .withNftReceiverPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, OWNER))
                        .withNftReceiverPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftCreditReceiverPrePostHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_PRE_POST_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_PRE_POST_ALLOWANCE_HOOK.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(0)
                        .maxSupply(1000L),
                mintToken(
                        "token",
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenAssociate(OWNER, "token"),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, OWNER))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, OWNER))
                        .withNftSenderPrePostHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, OWNER))
                        .withNftReceiverPrePostHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, OWNER))
                        .withNftReceiverPrePostHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftCreditPreOnlyHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(OWNER)
                        .withHooks(
                                accountAllowanceHook(123L, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(124L, TRUE_ALLOWANCE_HOOK.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                tokenAssociate(OWNER, "token"),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, OWNER))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, OWNER))
                        .withPreHookFor(OWNER, 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, OWNER))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> duplicateHookIdsInOneListFailsPrecheck() {
        final var OWNER = "acctDupIds";
        final var H1 = accountAllowanceHook(7L, FALSE_ALLOWANCE_HOOK.name());
        final var H2 = accountAllowanceHook(7L, FALSE_ALLOWANCE_HOOK.name());

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHook(H1)
                        .withHook(H2)
                        .hasPrecheck(HOOK_ID_REPEATED_IN_CREATION_DETAILS));
    }

    @HapiTest
    final Stream<DynamicTest> deleteHooks() {
        final var OWNER = "acctHeadRun";
        final long A = 1L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(accountAllowanceHook(A, FALSE_ALLOWANCE_HOOK.name())),
                cryptoUpdate(OWNER).removingHooks(A));
    }

    @HapiTest
    final Stream<DynamicTest> deleteHooksAndLinkNewOnes() {
        final var OWNER = "acctHeadRun";
        final long A = 1L, B = 2L, C = 3L, D = 4L, E = 5L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(
                                accountAllowanceHook(A, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(B, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(C, FALSE_ALLOWANCE_HOOK.name())),
                cryptoUpdate(OWNER)
                        .withHooks(
                                accountAllowanceHook(A, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(E, FALSE_ALLOWANCE_HOOK.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE),
                // Delete A,B (at head) and add D,E. Head should become D (the first in the creation list)
                cryptoUpdate(OWNER)
                        .removingHooks(A, B)
                        .withHooks(
                                accountAllowanceHook(D, FALSE_ALLOWANCE_HOOK.name()),
                                accountAllowanceHook(E, FALSE_ALLOWANCE_HOOK.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(D, a.firstHookId());
                    // started with 3; minus 2 deletes; plus 2 adds -> 3 again
                    assertEquals(3, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER)
                        .removingHooks(A)
                        .withHooks(accountAllowanceHook(A, FALSE_ALLOWANCE_HOOK.name()))
                        .hasKnownStatus(HOOK_NOT_FOUND),
                cryptoUpdate(OWNER).removingHooks(D).withHooks(accountAllowanceHook(D, FALSE_ALLOWANCE_HOOK.name())));
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
    final Stream<DynamicTest> storageAccessFromMappingWorks() {
        final var mappingSlot = Bytes.EMPTY;
        final AtomicReference<byte[]> defaultPayerMirror = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(OWNER).withHooks(accountAllowanceHook(124L, STORAGE_GET_MAPPING_HOOK.name())),
                withOpContext(
                        (spec, opLog) -> defaultPayerMirror.set(unhex(asHexedSolidityAddress(asAccount(spec, 2))))),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // Change the hook storage's mapping slot to have key 0x00 -> 0x01 so that the hook returns true
                // (the hook reads the mapping at slot 0, with key msg.sender; if the value is true, it
                // returns true)
                sourcing(() -> accountLambdaSStore(OWNER, 124L)
                        .putMappingEntry(
                                mappingSlot,
                                LambdaMappingEntry.newBuilder()
                                        .key(minimalKey(Bytes.wrap(defaultPayerMirror.get())))
                                        .value(Bytes.wrap(new byte[] {(byte) 0x01}))
                                        .build())
                        .signedBy(DEFAULT_PAYER, OWNER)),
                cryptoTransfer(TokenMovement.movingHbar(10).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteHookWithStorage() {
        final var OWNER = "acctHeadRun";
        final Bytes A = Bytes.wrap("a");
        final Bytes B = Bytes.wrap("Bb");
        final Bytes C = Bytes.wrap("cCc");
        final Bytes D = Bytes.fromHex("dddd");
        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(accountAllowanceHook(1L, FALSE_ALLOWANCE_HOOK.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(1L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(0, a.numberLambdaStorageSlots());
                }),
                accountLambdaSStore(OWNER, 1L).putSlot(A, B).putSlot(C, D),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(1L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(2, a.numberLambdaStorageSlots());
                }),
                cryptoUpdate(OWNER).removingHooks(1L).hasKnownStatus(HOOK_DELETION_REQUIRES_ZERO_STORAGE_SLOTS),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(1L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(2, a.numberLambdaStorageSlots());
                }));
    }
}
