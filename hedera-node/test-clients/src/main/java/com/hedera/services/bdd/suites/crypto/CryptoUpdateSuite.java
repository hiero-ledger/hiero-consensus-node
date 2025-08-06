// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyLabels.complex;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.ANY;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilOp.flatten;
import static com.hedera.services.bdd.spec.utilops.UtilStateChange.ecAccountsValidator;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.AUTO_CREATION_KEY_NAME_FN;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHip32Auto;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassWithoutBackgroundTrafficFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.visibleNonSyntheticItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyLabels;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilStateChange.ECKind;
import com.hedera.services.bdd.spec.utilops.grouping.InBlockingOrder;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
@SuppressWarnings("java:S1192") // "string literal should not be duplicated" - this rule makes test suites worse
public class CryptoUpdateSuite {
    private static final String TEST_ACCOUNT = "testAccount";
    private static final String TARGET_ACCOUNT = "complexKeyAccount";
    private static final String ACCOUNT_ALICE = "alice";
    private static final String ACCOUNT_PETER = "peter";
    private static final String ACCOUNT_PARKER = "parker";
    private static final String ACCOUNT_TONY = "tony";
    private static final String ACCOUNT_STARK = "stark";

    private static final String TOKEN_FUNGIBLE = "fungibleToken";

    private static final String REPEATING_KEY = "repeatingKey";
    private static final String ORIG_KEY = "origKey";
    private static final String UPD_KEY = "updKey";
    private static final String TARGET_KEY = "twoLevelThreshWithOverlap";
    private static final String MULTI_KEY = "multiKey";

    private static final SigControl twoLevelThresh = SigControl.threshSigs(
            2,
            SigControl.threshSigs(1, ANY, ANY, ANY, ANY, ANY, ANY, ANY),
            SigControl.threshSigs(3, ANY, ANY, ANY, ANY, ANY, ANY, ANY));
    private static final KeyLabels overlappingKeys =
            complex(complex("A", "B", "C", "D", "E", "F", "G"), complex("H", "I", "J", "K", "L", "M", "A"));

    private static final SigControl ENOUGH_UNIQUE_SIGS = SigControl.threshSigs(
            2,
            SigControl.threshSigs(1, OFF, OFF, OFF, OFF, OFF, OFF, ON),
            SigControl.threshSigs(3, ON, ON, ON, OFF, OFF, OFF, OFF));
    private static final SigControl NOT_ENOUGH_UNIQUE_SIGS = SigControl.threshSigs(
            2,
            SigControl.threshSigs(1, OFF, OFF, OFF, OFF, OFF, OFF, OFF),
            SigControl.threshSigs(3, ON, ON, ON, OFF, OFF, OFF, OFF));
    private static final SigControl ENOUGH_OVERLAPPING_SIGS = SigControl.threshSigs(
            2,
            SigControl.threshSigs(1, OFF, OFF, OFF, OFF, OFF, OFF, OFF),
            SigControl.threshSigs(3, ON, ON, OFF, OFF, OFF, OFF, ON));

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var stakedAccountId = 20;
        final var newStakedAccountId = 21;
        return hapiTest(
                cryptoCreate("user").stakedAccountId(stakedAccountId).declinedReward(true),
                submitModified(withSuccessivelyVariedBodyIds(), () -> cryptoUpdate("user")
                        .newStakedAccountId(newStakedAccountId)));
    }

    private static final UnaryOperator<String> ROTATION_TXN = account -> account + "KeyRotation";

    /*
      Test that long-zero EVM address has the expected value before and after
      key rotation, and that hte record stream does not imply anything different.
    */
    @HapiTest
    final Stream<DynamicTest> keyRotationDoesNotChangeEvmAddressForLongZero() {
        final var accountsToCreate = new TreeMap<ECKind, String>((Comparator.comparing(Enum::ordinal)));
        accountsToCreate.put(ECKind.LONG_ZERO, ECKind.LONG_ZERO.name());
        final var accountsToHaveKeysRotated = accountsToCreate.values().stream().toList();
        final Map<ECKind, Address> evmAddresses = new HashMap<>(); // Collect addresses of created accounts here
        return hapiTest(flatten(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1)),

                // Validate (after all ops executed) that the keeys were rotated
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        visibleNonSyntheticItems(
                                keyRotationsValidator(accountsToHaveKeysRotated),
                                rotateAndCalculateAllTxnIds(accountsToHaveKeysRotated)),
                        Duration.ofSeconds(15)),

                // If the FileAlterationObserver just started the monitor, there's a chance we could miss the
                // first couple of creations, so wait for a new record file boundary
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),

                // Validate (after all ops executed) that our accounts did get created
                recordStreamMustIncludePassFrom(
                        visibleNonSyntheticItems(
                                ecAccountsValidator(evmAddresses, accountsToCreate),
                                accountsToHaveKeysRotated.toArray(new String[0])),
                        Duration.ofSeconds(15)),

                // Create the account with a long-zero EVM address
                cryptoCreate(accountsToCreate.get(ECKind.LONG_ZERO))
                        .via(accountsToCreate.get(ECKind.LONG_ZERO))
                        .keyShape(SECP256K1_ON)
                        .exposingEvmAddressTo(address -> evmAddresses.put(ECKind.LONG_ZERO, address)),

                // Save all EVM addresses
                saveAllEvmAddresses(evmAddresses, accountsToCreate),
                rotateKeys(accountsToHaveKeysRotated),

                // --- ROTATE KEYS ---
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted)));
    }

    /*
      Test that auto and hollow EVM address has the expected value before and after
      key rotation, and that hte record stream does not imply anything different.
    */
    @HapiTest
    final Stream<DynamicTest> keyRotationDoesNotChangeEvmAddressForAutoAndHollow() {
        final var accountsToCreate = new TreeMap<ECKind, String>((Comparator.comparing(Enum::ordinal)));
        accountsToCreate.put(ECKind.AUTO, ECKind.AUTO.name().toLowerCase());
        accountsToCreate.put(ECKind.HOLLOW, ECKind.HOLLOW.name().toLowerCase());
        final var accountsToHaveKeysRotated = accountsToCreate.values().stream().toList();
        final Map<ECKind, Address> evmAddresses = new HashMap<>();
        return hapiTest(flatten(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1)),

                // Validate (after all ops executed) that the keeys were rotated
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        visibleNonSyntheticItems(
                                keyRotationsValidator(accountsToHaveKeysRotated),
                                rotateAndCalculateAllTxnIds(accountsToHaveKeysRotated)),
                        Duration.ofSeconds(15)),

                // If the FileAlterationObserver just started the monitor, there's a chance we could miss the
                // first couple of creations, so wait for a new record file boundary
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),

                // Validate (after all ops executed) that our accounts did get created
                recordStreamMustIncludePassFrom(
                        visibleNonSyntheticItems(
                                ecAccountsValidator(evmAddresses, accountsToCreate),
                                accountsToHaveKeysRotated.toArray(new String[0])),
                        Duration.ofSeconds(15)),

                // Auto-create an account with an ECDSA key alias
                createHip32Auto(1, KeyShape.SECP256K1, i -> accountsToCreate.get(ECKind.AUTO)),
                withAddressOfKey(accountsToCreate.get(ECKind.AUTO), evmAddress -> {
                    evmAddresses.put(ECKind.AUTO, evmAddress);
                    return withOpContext((spec, opLog) -> spec.registry()
                            .saveTxnId(
                                    accountsToCreate.get(ECKind.AUTO),
                                    spec.registry().getTxnId("hip32" + AUTO_CREATION_KEY_NAME_FN.apply(0))));
                }),

                // Create a hollow account and complete it
                createHollow(
                        1,
                        i -> accountsToCreate.get(ECKind.HOLLOW),
                        evmAddress -> cryptoTransfer(tinyBarsFromTo(GENESIS, evmAddress, ONE_HUNDRED_HBARS))),
                withAddressOfKey(accountsToCreate.get(ECKind.HOLLOW), evmAddress -> {
                    evmAddresses.put(ECKind.HOLLOW, evmAddress);
                    return withOpContext((spec, opLog) -> {
                        spec.registry()
                                .saveTxnId(
                                        accountsToCreate.get(ECKind.HOLLOW),
                                        spec.registry()
                                                .getTxnId(accountsToCreate.get(ECKind.AUTO)
                                                        + "Create" /*from UtilVerbs.createHollow*/ + evmAddress));
                    });
                }),
                cryptoTransfer(tinyBarsFromTo(accountsToCreate.get(ECKind.HOLLOW), FUNDING, 1))
                        .payingWith(accountsToCreate.get(ECKind.HOLLOW))
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(accountsToCreate.get(ECKind.HOLLOW))),
                saveAllEvmAddresses(evmAddresses, accountsToCreate),
                rotateKeys(accountsToHaveKeysRotated),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted)));
    }

    /*
    Test that explicit alias EVM address has the expected value before and after
    key rotation, and that hte record stream does not imply anything different.
     */
    @HapiTest
    final Stream<DynamicTest> keyRotationDoesNotChangeEvmAddressForExplicitAlias() {
        final var accountsToCreate = new TreeMap<ECKind, String>((Comparator.comparing(Enum::ordinal)));
        accountsToCreate.put(ECKind.EXPLICIT_ALIAS, ECKind.EXPLICIT_ALIAS.name());
        final var accountsToHaveKeysRotated = accountsToCreate.values().stream().toList();
        final Map<ECKind, Address> evmAddresses = new HashMap<>(); // Collect addresses of created accounts here
        return hapiTest(flatten(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1)),

                // Validate (after all ops executed) that the keeys were rotated
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        visibleNonSyntheticItems(
                                keyRotationsValidator(accountsToHaveKeysRotated),
                                rotateAndCalculateAllTxnIds(accountsToHaveKeysRotated)),
                        Duration.ofSeconds(15)),

                // If the FileAlterationObserver just started the monitor, there's a chance we could miss the
                // first couple of creations, so wait for a new record file boundary
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),

                // Validate (after all ops executed) that our accounts did get created
                recordStreamMustIncludePassFrom(
                        visibleNonSyntheticItems(
                                ecAccountsValidator(evmAddresses, accountsToCreate),
                                accountsToHaveKeysRotated.toArray(new String[0])),
                        Duration.ofSeconds(15)),

                // Create an account with an explicit EVM address
                newKeyNamed(accountsToCreate.get(ECKind.EXPLICIT_ALIAS)).shape(KeyShape.SECP256K1),
                withAddressOfKey(accountsToCreate.get(ECKind.EXPLICIT_ALIAS), evmAddress -> {
                    evmAddresses.put(ECKind.EXPLICIT_ALIAS, evmAddress);
                    return cryptoCreate(accountsToCreate.get(ECKind.EXPLICIT_ALIAS))
                            .key(accountsToCreate.get(ECKind.EXPLICIT_ALIAS))
                            .evmAddress(evmAddress)
                            .via(accountsToCreate.get(ECKind.EXPLICIT_ALIAS));
                }),
                saveAllEvmAddresses(evmAddresses, accountsToCreate),
                rotateKeys(accountsToHaveKeysRotated),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted)));
    }

    private static VisibleItemsValidator keyRotationsValidator(@NonNull final List<String> accountsToHaveKeysRotated) {
        return (spec, records) -> {
            final var rotationTxnIds =
                    accountsToHaveKeysRotated.stream().map(ROTATION_TXN).toArray(String[]::new);
            for (final var txnId : rotationTxnIds) {
                final var successItems = requireNonNull(records.get(txnId), txnId + " not found");
                final var updateEntry = successItems.entries().stream()
                        .filter(entry -> entry.function() == CryptoUpdate)
                        .findFirst()
                        .orElseThrow();
                assertEquals(0, updateEntry.txnRecord().getEvmAddress().size(), "for txnId " + txnId);
            }
        };
    }

    private InBlockingOrder rotateKeys(List<String> accountsToHaveKeysRotated) {
        return blockingOrder(IntStream.range(0, accountsToHaveKeysRotated.size())
                .mapToObj(i -> {
                    final var newKey = "replKey" + i;
                    final var targetAccount = accountsToHaveKeysRotated.get(i);
                    return blockingOrder(
                            newKeyNamed(newKey).shape(KeyShape.SECP256K1),
                            cryptoUpdate(targetAccount).key(newKey).via(ROTATION_TXN.apply(targetAccount)));
                })
                .toArray(SpecOperation[]::new));
    }

    private CustomSpecAssert saveAllEvmAddresses(
            Map<ECKind, Address> evmAddresses, Map<ECKind, String> accountsToCreate) {
        return withOpContext((spec, opLog) -> {
            for (final var e : evmAddresses.entrySet()) {
                spec.registry()
                        .saveEVMAddress(
                                accountsToCreate.get(e.getKey()),
                                e.getValue().value().toString(16));
            }
        });
    }

    private String[] rotateAndCalculateAllTxnIds(List<String> accountsToHaveKeysRotated) {
        final var allTxnIds = Stream.concat(
                        accountsToHaveKeysRotated.stream(),
                        accountsToHaveKeysRotated.stream().map(ROTATION_TXN))
                .toArray(String[]::new);
        return allTxnIds;
    }

    @HapiTest
    final Stream<DynamicTest> updateForMaxAutoAssociationsForAccountsWorks() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT_ALICE).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(ACCOUNT_PETER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(-1),
                cryptoCreate(ACCOUNT_TONY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ACCOUNT_STARK).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(-1),
                cryptoCreate(ACCOUNT_PARKER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(-1),
                tokenCreate(TOKEN_FUNGIBLE)
                        .initialSupply(1000L)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(ACCOUNT_ALICE)
                        .via("tokenCreate"),
                tokenAssociate(ACCOUNT_PETER, TOKEN_FUNGIBLE),
                tokenAssociate(ACCOUNT_TONY, TOKEN_FUNGIBLE),
                // Update Alice
                cryptoUpdate(ACCOUNT_ALICE).maxAutomaticAssociations(0),
                getAccountInfo(ACCOUNT_ALICE).hasMaxAutomaticAssociations(0),
                cryptoUpdate(ACCOUNT_ALICE).maxAutomaticAssociations(-1),
                getAccountInfo(ACCOUNT_ALICE).hasMaxAutomaticAssociations(-1),
                // Update Tony
                cryptoUpdate(ACCOUNT_TONY).maxAutomaticAssociations(1),
                getAccountInfo(ACCOUNT_TONY).hasMaxAutomaticAssociations(1),
                // Update Stark
                cryptoUpdate(ACCOUNT_STARK).maxAutomaticAssociations(-1),
                getAccountInfo(ACCOUNT_STARK).hasMaxAutomaticAssociations(-1),
                // Update Peter
                cryptoUpdate(ACCOUNT_PETER).maxAutomaticAssociations(-1),
                getAccountInfo(ACCOUNT_PETER).hasMaxAutomaticAssociations(-1),
                cryptoUpdate(ACCOUNT_PETER).maxAutomaticAssociations(0),
                getAccountInfo(ACCOUNT_PETER).hasMaxAutomaticAssociations(0),
                // Update Parker
                cryptoUpdate(ACCOUNT_PARKER).maxAutomaticAssociations(1),
                getAccountInfo(ACCOUNT_PARKER).hasMaxAutomaticAssociations(1),
                getTxnRecord("tokenCreate").hasNewTokenAssociation(TOKEN_FUNGIBLE, ACCOUNT_ALICE));
    }

    @HapiTest
    final Stream<DynamicTest> updateStakingFieldsWorks() {
        final var stakedAccountId = 20;
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate("user").key(ADMIN_KEY).stakedAccountId(20).declinedReward(true),
                getAccountInfo("user")
                        .has(accountWith()
                                .stakedAccountId(stakedAccountId)
                                .noStakingNodeId()
                                .isDeclinedReward(true)),
                cryptoUpdate("user").newStakedNodeId(0L).newDeclinedReward(false),
                getAccountInfo("user")
                        .has(accountWith().noStakedAccountId().stakedNodeId(0L).isDeclinedReward(false)),
                cryptoUpdate("user").newStakedNodeId(-1L),
                cryptoUpdate("user").newStakedNodeId(-25L).hasKnownStatus(INVALID_STAKING_ID),
                getAccountInfo("user")
                        .has(accountWith().noStakedAccountId().noStakingNodeId().isDeclinedReward(false)),
                cryptoUpdate("user").key(ADMIN_KEY).newStakedAccountId("20").newDeclinedReward(true),
                getAccountInfo("user")
                        .has(accountWith()
                                .stakedAccountId(stakedAccountId)
                                .noStakingNodeId()
                                .isDeclinedReward(true))
                        .logged(),
                cryptoUpdate("user").key(ADMIN_KEY).newStakedAccountId("0.0.0"),
                getAccountInfo("user")
                        .has(accountWith().noStakedAccountId().noStakingNodeId().isDeclinedReward(true))
                        .logged(),
                // For completeness stake back to a node
                cryptoUpdate("user").key(ADMIN_KEY).newStakedNodeId(1),
                getAccountInfo("user").has(accountWith().stakedNodeId(1L).isDeclinedReward(true)));
    }

    @LeakyHapiTest(overrides = {"entities.maxLifetime", "ledger.maxAutoAssociations"})
    final Stream<DynamicTest> usdFeeAsExpectedCryptoUpdate() {
        double baseFee = 0.000214;
        double baseFeeWithExpiry = 0.00022;

        final var baseTxn = "baseTxn";
        final var plusOneTxn = "plusOneTxn";
        final var plusTenTxn = "plusTenTxn";
        final var plusFiveKTxn = "plusFiveKTxn";
        final var plusFiveKAndOneTxn = "plusFiveKAndOneTxn";
        final var invalidNegativeTxn = "invalidNegativeTxn";
        final var validNegativeTxn = "validNegativeTxn";
        final var allowedPercentDiff = 1.5;

        AtomicLong expiration = new AtomicLong();
        return hapiTest(
                overridingTwo(
                        "ledger.maxAutoAssociations", "5000",
                        "entities.maxLifetime", "3153600000"),
                newKeyNamed("key").shape(SIMPLE),
                cryptoCreate("payer").key("key").balance(1_000 * ONE_HBAR),
                cryptoCreate("canonicalAccount")
                        .key("key")
                        .balance(100 * ONE_HBAR)
                        .autoRenewSecs(THREE_MONTHS_IN_SECONDS)
                        .blankMemo()
                        .payingWith("payer"),
                cryptoCreate("autoAssocTarget")
                        .key("key")
                        .balance(100 * ONE_HBAR)
                        .autoRenewSecs(THREE_MONTHS_IN_SECONDS)
                        .blankMemo()
                        .payingWith("payer"),
                getAccountInfo("canonicalAccount").exposingExpiry(expiration::set),
                sourcing(() -> cryptoUpdate("canonicalAccount")
                        .payingWith("canonicalAccount")
                        .expiring(expiration.get() + THREE_MONTHS_IN_SECONDS)
                        .blankMemo()
                        .via(baseTxn)),
                getAccountInfo("canonicalAccount")
                        .hasMaxAutomaticAssociations(0)
                        .logged(),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(1)
                        .via(plusOneTxn),
                getAccountInfo("autoAssocTarget").hasMaxAutomaticAssociations(1).logged(),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(11)
                        .via(plusTenTxn),
                getAccountInfo("autoAssocTarget")
                        .hasMaxAutomaticAssociations(11)
                        .logged(),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(5000)
                        .via(plusFiveKTxn),
                getAccountInfo("autoAssocTarget")
                        .hasMaxAutomaticAssociations(5000)
                        .logged(),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(-1000)
                        .via(invalidNegativeTxn)
                        .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(5001)
                        .via(plusFiveKAndOneTxn)
                        .hasKnownStatus(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(-1)
                        .via(validNegativeTxn),
                getAccountInfo("autoAssocTarget")
                        .hasMaxAutomaticAssociations(-1)
                        .logged(),
                validateChargedUsd(baseTxn, baseFeeWithExpiry, allowedPercentDiff),
                validateChargedUsd(plusOneTxn, baseFee, allowedPercentDiff),
                validateChargedUsd(plusTenTxn, baseFee, allowedPercentDiff),
                validateChargedUsd(plusFiveKTxn, baseFee, allowedPercentDiff),
                validateChargedUsd(validNegativeTxn, baseFee, allowedPercentDiff));
    }

    @HapiTest
    final Stream<DynamicTest> updateFailsWithOverlyLongLifetime() {
        return hapiTest(
                cryptoCreate(TARGET_ACCOUNT),
                doWithStartupConfigNow("entities.maxLifetime", (value, now) -> cryptoUpdate(TARGET_ACCOUNT)
                        .expiring(now.getEpochSecond() + Long.parseLong(value) + 12345L)
                        .hasKnownStatus(INVALID_EXPIRATION_TIME)));
    }

    @HapiTest
    final Stream<DynamicTest> sysAccountKeyUpdateBySpecialWontNeedNewKeyTxnSign() {
        String sysAccount = "99";
        String randomAccount = "randomAccount";
        String firstKey = "firstKey";
        String secondKey = "secondKey";

        return hapiTest(
                newKeyNamed(firstKey).shape(SIMPLE),
                newKeyNamed(secondKey).shape(SIMPLE),
                cryptoCreate(randomAccount).key(firstKey),
                cryptoUpdate(sysAccount)
                        .key(secondKey)
                        .signedBy(GENESIS)
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS)
                        .logged(),
                cryptoUpdate(randomAccount)
                        .key(secondKey)
                        .signedBy(firstKey)
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> canUpdateMemo() {
        String firstMemo = "First";
        String secondMemo = "Second";
        return hapiTest(
                cryptoCreate(TARGET_ACCOUNT).balance(0L).entityMemo(firstMemo),
                cryptoUpdate(TARGET_ACCOUNT).entityMemo(ZERO_BYTE_MEMO).hasPrecheck(INVALID_ZERO_BYTE_IN_STRING),
                cryptoUpdate(TARGET_ACCOUNT).entityMemo(secondMemo),
                getAccountDetails(TARGET_ACCOUNT)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().memo(secondMemo)));
    }

    @HapiTest
    final Stream<DynamicTest> updateWithUniqueSigs() {
        return hapiTest(
                newKeyNamed(TARGET_KEY).shape(twoLevelThresh).labels(overlappingKeys),
                cryptoCreate(TARGET_ACCOUNT).key(TARGET_KEY),
                cryptoUpdate(TARGET_ACCOUNT)
                        .sigControl(forKey(TARGET_KEY, ENOUGH_UNIQUE_SIGS))
                        .receiverSigRequired(true));
    }

    @HapiTest
    final Stream<DynamicTest> updateWithOneEffectiveSig() {
        KeyLabels oneUniqueKey =
                complex(complex("X", "X", "X", "X", "X", "X", "X"), complex("X", "X", "X", "X", "X", "X", "X"));
        SigControl singleSig = SigControl.threshSigs(
                2,
                SigControl.threshSigs(1, OFF, OFF, OFF, OFF, OFF, OFF, OFF),
                SigControl.threshSigs(3, OFF, OFF, OFF, ON, OFF, OFF, OFF));

        return hapiTest(
                newKeyNamed(REPEATING_KEY).shape(twoLevelThresh).labels(oneUniqueKey),
                cryptoCreate(TARGET_ACCOUNT).key(REPEATING_KEY).balance(1_000_000_000L),
                cryptoUpdate(TARGET_ACCOUNT)
                        .sigControl(forKey(REPEATING_KEY, singleSig))
                        .receiverSigRequired(true)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> updateWithOverlappingSigs() {
        return hapiTest(
                newKeyNamed(TARGET_KEY).shape(twoLevelThresh).labels(overlappingKeys),
                cryptoCreate(TARGET_ACCOUNT).key(TARGET_KEY),
                cryptoUpdate(TARGET_ACCOUNT)
                        .sigControl(forKey(TARGET_KEY, ENOUGH_OVERLAPPING_SIGS))
                        .receiverSigRequired(true)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> updateFailsWithContractKey() {
        final var id = new AtomicReference<ContractID>();
        final var CONTRACT = "Multipurpose";
        return hapiTest(
                cryptoCreate(TARGET_ACCOUNT),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).exposingContractIdTo(id::set),
                sourcing(() -> cryptoUpdate(TARGET_ACCOUNT)
                        .protoKey(Key.newBuilder().setContractID(id.get()).build())
                        .hasKnownStatus(INVALID_SIGNATURE)));
    }

    @HapiTest
    final Stream<DynamicTest> updateFailsWithInsufficientSigs() {
        return hapiTest(
                newKeyNamed(TARGET_KEY).shape(twoLevelThresh).labels(overlappingKeys),
                cryptoCreate(TARGET_ACCOUNT).key(TARGET_KEY),
                cryptoUpdate(TARGET_ACCOUNT)
                        .sigControl(forKey(TARGET_KEY, NOT_ENOUGH_UNIQUE_SIGS))
                        .receiverSigRequired(true)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> cannotSetThresholdNegative() {
        return hapiTest(cryptoCreate(TEST_ACCOUNT), cryptoUpdate(TEST_ACCOUNT).sendThreshold(-1L));
    }

    @HapiTest
    final Stream<DynamicTest> updateFailsIfMissingSigs() {
        SigControl origKeySigs = SigControl.threshSigs(3, ON, ON, SigControl.threshSigs(1, OFF, ON));
        SigControl updKeySigs = SigControl.listSigs(ON, OFF, SigControl.threshSigs(1, ON, OFF, OFF, OFF));

        return hapiTest(
                newKeyNamed(ORIG_KEY).shape(origKeySigs),
                newKeyNamed(UPD_KEY).shape(updKeySigs),
                cryptoCreate(TEST_ACCOUNT)
                        .receiverSigRequired(true)
                        .key(ORIG_KEY)
                        .sigControl(forKey(ORIG_KEY, origKeySigs)),
                cryptoUpdate(TEST_ACCOUNT)
                        .key(UPD_KEY)
                        .sigControl(forKey(TEST_ACCOUNT, origKeySigs), forKey(UPD_KEY, updKeySigs))
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> updateWithEmptyKeyFails() {
        SigControl updKeySigs = threshOf(0, 0);

        return hapiTest(
                newKeyNamed(ORIG_KEY).shape(KeyShape.SIMPLE),
                newKeyNamed(UPD_KEY).shape(updKeySigs),
                cryptoCreate(TEST_ACCOUNT).key(ORIG_KEY),
                cryptoUpdate(TEST_ACCOUNT).key(UPD_KEY).hasPrecheck(INVALID_ADMIN_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> updateMaxAutoAssociationsWorks() {
        final int maxAllowedAssociations = 5000;
        final int originalMax = 2;
        final int newBadMax = originalMax - 1;
        final int newGoodMax = originalMax + 1;
        final String tokenA = "tokenA";
        final String tokenB = "tokenB";

        final String treasury = "treasury";
        final String tokenACreate = "tokenACreate";
        final String tokenBCreate = "tokenBCreate";
        final String transferAToC = "transferAToC";
        final String transferBToC = "transferBToC";
        final String CONTRACT = "Multipurpose";
        final String ADMIN_KEY = "adminKey";

        return hapiTest(
                cryptoCreate(treasury).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(ADMIN_KEY).maxAutomaticTokenAssociations(originalMax),
                tokenCreate(tokenA)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(treasury)
                        .via(tokenACreate),
                getTxnRecord(tokenACreate).hasNewTokenAssociation(tokenA, treasury),
                tokenCreate(tokenB)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(treasury)
                        .via(tokenBCreate),
                getTxnRecord(tokenBCreate).hasNewTokenAssociation(tokenB, treasury),
                getContractInfo(CONTRACT).has(ContractInfoAsserts.contractWith().maxAutoAssociations(originalMax)),
                cryptoTransfer(moving(1, tokenA).between(treasury, CONTRACT)).via(transferAToC),
                getTxnRecord(transferAToC).hasNewTokenAssociation(tokenA, CONTRACT),
                cryptoTransfer(moving(1, tokenB).between(treasury, CONTRACT)).via(transferBToC),
                getTxnRecord(transferBToC).hasNewTokenAssociation(tokenB, CONTRACT),
                getContractInfo(CONTRACT)
                        .payingWith(GENESIS)
                        .has(contractWith()
                                .hasAlreadyUsedAutomaticAssociations(originalMax)
                                .maxAutoAssociations(originalMax)),
                contractUpdate(CONTRACT)
                        .newMaxAutomaticAssociations(newBadMax)
                        .hasKnownStatus(EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(newGoodMax),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(newGoodMax)),
                contractUpdate(CONTRACT)
                        .newMaxAutomaticAssociations(maxAllowedAssociations + 1)
                        .hasKnownStatus(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(-2).hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(-1).hasKnownStatus(SUCCESS),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(-1)));
    }

    @HapiTest
    final Stream<DynamicTest> deletedAccountCannotBeUpdated() {
        final var accountToDelete = "accountToDelete";
        return hapiTest(
                cryptoCreate(accountToDelete).declinedReward(false),
                cryptoDelete(accountToDelete),
                cryptoUpdate(accountToDelete)
                        .payingWith(DEFAULT_PAYER)
                        .newDeclinedReward(true)
                        .hasKnownStatus(ACCOUNT_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> withNegativeExpiry() {
        final var account = "acc";
        return hapiTest(
                cryptoCreate(account).declinedReward(false),
                cryptoUpdate(account).payingWith(DEFAULT_PAYER).expiring(-1).hasKnownStatus(INVALID_EXPIRATION_TIME));
    }
}
