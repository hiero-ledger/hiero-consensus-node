// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.STANDARD_PERMISSIBLE_OUTCOMES;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.STANDARD_PERMISSIBLE_PRECHECKS;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUtf8Bytes;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withLongZeroAddress;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.FALSE_VALUE;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.PAYER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ALIAS_ALREADY_ASSIGNED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.RealmID;
import com.hederahashgraph.api.proto.java.ShardID;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class CryptoCreateSuite {
    public static final String ACCOUNT = "account";
    public static final String ANOTHER_ACCOUNT = "anotherAccount";
    public static final String ED_25519_KEY = "ed25519Alias";
    public static final String ACCOUNT_ID = asEntityString(10);
    public static final String STAKED_ACCOUNT_ID = asEntityString(3);
    public static final String CIVILIAN = "civilian";
    public static final String NO_KEYS = "noKeys";
    public static final String SHORT_KEY = "shortKey";
    public static final String EMPTY_KEY_STRING = "emptyKey";
    private static final String ED_KEY = "EDKEY";

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(submitModified(
                withSuccessivelyVariedBodyIds(), () -> cryptoCreate("account").stakedAccountId(STAKED_ACCOUNT_ID)));
    }

    @HapiTest
    public Stream<DynamicTest> cantCreateTwoAccountsWithSameAlias() {
        final String ecKey = "ecKey";
        final String key1 = "key1";
        final String key2 = "key2";

        return hapiTest(
                newKeyNamed(ecKey).shape(SECP_256K1_SHAPE),
                newKeyNamed(key1),
                newKeyNamed(key2),
                cryptoCreate(PAYER).balance(10 * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var key = registry.getKey(ecKey);
                    final var evmAddress = ByteString.copyFrom(
                            recoverAddressFromPubKey(key.getECDSASecp256K1().toByteArray()));
                    final var op1 = cryptoCreate("account1")
                            .balance(ONE_HBAR)
                            .key(key1)
                            .alias(key.toByteString())
                            .signedBy(key1, PAYER)
                            .payingWith(PAYER)
                            .hasKnownStatus(INVALID_ALIAS_KEY);
                    final var op2 = cryptoCreate("evmAccount")
                            .balance(ONE_HBAR)
                            .key(key2)
                            .signedBy(key2, ecKey, PAYER)
                            .alias(evmAddress)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(ecKey))
                            .payingWith(PAYER)
                            .via("creation");
                    final var op4 = getAccountBalance("evmAccount").hasTinyBars(ONE_HBAR);
                    final var op5 = scheduleCreate(
                                    "createKeyAliasAccount",
                                    cryptoCreate("account1")
                                            .balance(ONE_HBAR)
                                            .key(key1)
                                            .alias(key.toByteString())
                                            .payingWith(PAYER))
                            .alsoSigningWith(key1, ecKey, PAYER)
                            .via("scheduleCreate")
                            .recordingScheduledTxn();

                    final var op6 = getTxnRecord("scheduleCreate")
                            .scheduled()
                            .hasPriority(recordWith().status(ALIAS_ALREADY_ASSIGNED))
                            .logged();
                    final var op7 = getScheduleInfo("createKeyAliasAccount")
                            .isExecuted()
                            .hasRecordedScheduledTxn();
                    allRunFor(spec, op1, op2, op4, op5, op6, op7);
                }));
    }

    @HapiTest
    @DisplayName("canonical EVM addresses are determined by aliases")
    final Stream<DynamicTest> canonicalEvmAddressesDeterminedByAliases(
            @Contract(contract = "MakeCalls") SpecContract makeCalls) {
        return hapiTest(
                newKeyNamed("oneKey").shape(SECP256K1_ON),
                newKeyNamed("twoKey").shape(SECP256K1_ON),
                cryptoCreate("doSomething"),
                cryptoCreate("firstUser").balance(0L).key("oneKey").via("createNoAlias"),
                cryptoCreate("secondUser")
                        .key("twoKey")
                        .balance(0L)
                        .withMatchingEvmAddress()
                        .via("createWithAlias"),
                // Since this is not the canonical (long-zero) address for the receiver, tries and fails lazy creation
                withAddressOfKey("oneKey", address -> makeCalls
                        .call("makeCallWithAmount", address, new byte[0])
                        .andAssert(txn -> txn.gas(25_000L).sending(1L).hasKnownStatus(INSUFFICIENT_GAS))),
                withLongZeroAddress("firstUser", address -> makeCalls
                        .call("makeCallWithAmount", address, new byte[0])
                        .andAssert(txn -> txn.sending(1L))),
                // Since this is not the canonical (EIP-1014) address for the receiver, tries and fails lazy creation
                withLongZeroAddress("secondUser", address -> makeCalls
                        .call("makeCallWithAmount", address, new byte[0])
                        .andAssert(txn -> txn.gas(25_000L).sending(1L).hasKnownStatus(INSUFFICIENT_GAS))),
                withAddressOfKey("twoKey", address -> makeCalls
                        .call("makeCallWithAmount", address, new byte[0])
                        .andAssert(txn -> txn.sending(1L))),
                getAccountBalance("secondUser").hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithStakingFields() {
        return hapiTest(
                cryptoCreate("civilianWORewardStakingNode")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(true)
                        .stakedNodeId(0),
                getAccountInfo("civilianWORewardStakingNode")
                        .has(accountWith()
                                .isDeclinedReward(true)
                                .noStakedAccountId()
                                .stakedNodeId(0)),
                cryptoCreate("civilianWORewardStakingAcc")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(true)
                        .stakedAccountId(ACCOUNT_ID),
                getAccountInfo("civilianWORewardStakingAcc")
                        .has(accountWith()
                                .isDeclinedReward(true)
                                .noStakingNodeId()
                                .stakedAccountId(ACCOUNT_ID)),
                cryptoCreate("civilianWRewardStakingNode")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(false)
                        .stakedNodeId(0),
                getAccountInfo("civilianWRewardStakingNode")
                        .has(accountWith()
                                .isDeclinedReward(false)
                                .noStakedAccountId()
                                .stakedNodeId(0)),
                cryptoCreate("civilianWRewardStakingAcc")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(false)
                        .stakedAccountId(ACCOUNT_ID),
                getAccountInfo("civilianWRewardStakingAcc")
                        .has(accountWith()
                                .isDeclinedReward(false)
                                .noStakingNodeId()
                                .stakedAccountId(ACCOUNT_ID)),
                /* --- sentiel values throw */
                cryptoCreate("invalidStakedAccount")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(false)
                        .stakedAccountId("0.0.0")
                        .hasPrecheck(INVALID_STAKING_ID),
                cryptoCreate("invalidStakedNode")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(false)
                        .stakedNodeId(-1L)
                        .hasPrecheck(INVALID_STAKING_ID));
    }

    @HapiTest
    final Stream<DynamicTest> cannotCreateAnAccountWithLongZeroKeyButCanUseEvmAddress() {
        final AtomicReference<ByteString> secp256k1Key = new AtomicReference<>();
        final AtomicReference<ByteString> evmAddress = new AtomicReference<>();
        final var ecdsaKey = "ecdsaKey";
        final var longZeroAddress = ByteString.copyFrom(CommonUtils.unhex("0000000000000000000000000000000fffffffff"));
        final var creation = "creation";
        return hapiTest(
                cryptoCreate(ACCOUNT).evmAddress(longZeroAddress).hasPrecheck(INVALID_ALIAS_KEY),
                newKeyNamed(ecdsaKey).shape(SECP256K1_ON),
                withOpContext((spec, opLog) -> {
                    secp256k1Key.set(spec.registry().getKey(ecdsaKey).toByteString());
                    final var rawAddress = recoverAddressFromPubKey(
                            spec.registry().getKey(ecdsaKey).getECDSASecp256K1().toByteArray());
                    evmAddress.set(ByteString.copyFrom(rawAddress));
                }),
                sourcing(() -> cryptoCreate(ACCOUNT)
                        .key(ecdsaKey)
                        .alias(evmAddress.get())
                        .via(creation)),
                sourcing(() -> getTxnRecord(creation).logged()));
    }

    @LeakyHapiTest(overrides = {"entities.unlimitedAutoAssociationsEnabled"})
    final Stream<DynamicTest> createFailsIfMaxAutoAssocIsNegativeAndUnlimitedFlagDisabled() {
        return hapiTest(
                overriding("entities.unlimitedAutoAssociationsEnabled", FALSE_VALUE),
                cryptoCreate(CIVILIAN)
                        .balance(0L)
                        .maxAutomaticTokenAssociations(-1)
                        .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                cryptoCreate(CIVILIAN)
                        .balance(0L)
                        .maxAutomaticTokenAssociations(-2)
                        .hasPrecheck(INVALID_MAX_AUTO_ASSOCIATIONS),
                cryptoCreate(CIVILIAN)
                        .balance(0L)
                        .maxAutomaticTokenAssociations(-1000)
                        .hasPrecheck(INVALID_MAX_AUTO_ASSOCIATIONS),
                cryptoCreate(CIVILIAN)
                        .balance(0L)
                        .maxAutomaticTokenAssociations(Integer.MIN_VALUE)
                        .hasPrecheck(INVALID_MAX_AUTO_ASSOCIATIONS));
    }

    @HapiTest
    final Stream<DynamicTest> syntaxChecksAreAsExpected() {
        return hapiTest(
                cryptoCreate("broken").autoRenewSecs(1L).hasPrecheck(AUTORENEW_DURATION_NOT_IN_RANGE),
                cryptoCreate("alsoBroken").entityMemo(ZERO_BYTE_MEMO).hasPrecheck(INVALID_ZERO_BYTE_IN_STRING));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountEmptyThresholdKey() {
        KeyShape shape = threshOf(0, 0);
        long initialBalance = 10_000L;

        return hapiTest(cryptoCreate(NO_KEYS)
                .keyShape(shape)
                .balance(initialBalance)
                .logged()
                .hasPrecheck(KEY_REQUIRED));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountEmptyKeyList() {
        KeyShape shape = listOf(0);
        long initialBalance = 10_000L;
        ShardID shardID = ShardID.newBuilder().build();
        RealmID realmID = RealmID.newBuilder().build();

        return hapiTest(
                cryptoCreate(NO_KEYS)
                        .keyShape(shape)
                        .balance(initialBalance)
                        .shardId(shardID)
                        .realmId(realmID)
                        .logged()
                        .hasPrecheck(KEY_REQUIRED)
                // In modular code this error is thrown in handle, but it is fixed using dynamic property
                // spec.streamlinedIngestChecks
                // to accommodate error codes moved from Ingest to handle
                );
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountEmptyNestedKey() {
        KeyShape emptyThresholdShape = threshOf(0, 0);
        KeyShape emptyListShape = listOf(0);
        KeyShape shape = threshOf(2, emptyThresholdShape, emptyListShape);
        long initialBalance = 10_000L;

        return hapiTest(cryptoCreate(NO_KEYS)
                .keyShape(shape)
                .balance(initialBalance)
                .logged()
                .hasPrecheck(KEY_REQUIRED));
    }

    // One of element in key list is not valid
    @HapiTest
    final Stream<DynamicTest> createAnAccountInvalidKeyList() {
        KeyShape emptyThresholdShape = threshOf(0, 0);
        KeyShape shape = listOf(SIMPLE, SIMPLE, emptyThresholdShape);
        long initialBalance = 10_000L;

        return hapiTest(cryptoCreate(NO_KEYS)
                .keyShape(shape)
                .balance(initialBalance)
                .logged()
                .hasPrecheck(INVALID_ADMIN_KEY));
    }

    // One of element in nested key list is not valid
    @HapiTest
    final Stream<DynamicTest> createAnAccountInvalidNestedKeyList() {
        KeyShape invalidListShape = listOf(SIMPLE, SIMPLE, listOf(0));
        KeyShape shape = listOf(SIMPLE, SIMPLE, invalidListShape);
        long initialBalance = 10_000L;

        return hapiTest(cryptoCreate(NO_KEYS)
                .keyShape(shape)
                .balance(initialBalance)
                .logged()
                .hasPrecheck(INVALID_ADMIN_KEY));
    }

    // One of element in threshold key is not valid
    @HapiTest
    final Stream<DynamicTest> createAnAccountInvalidThresholdKey() {
        KeyShape emptyListShape = listOf(0);
        KeyShape thresholdShape = threshOf(1, SIMPLE, SIMPLE, emptyListShape);
        long initialBalance = 10_000L;

        // build a threshold key with one of key is invalid
        Key randomKey1 = Key.newBuilder()
                .setEd25519(ByteString.copyFrom(randomUtf8Bytes(32)))
                .build();
        Key randomKey2 = Key.newBuilder()
                .setEd25519(ByteString.copyFrom(randomUtf8Bytes(32)))
                .build();
        Key shortKey =
                Key.newBuilder().setEd25519(ByteString.copyFrom(new byte[10])).build();

        KeyList invalidKeyList = KeyList.newBuilder()
                .addKeys(randomKey1)
                .addKeys(randomKey2)
                .addKeys(shortKey)
                .build();

        ThresholdKey invalidThresholdKey = ThresholdKey.newBuilder()
                .setThreshold(2)
                .setKeys(invalidKeyList)
                .build();

        Key regKey1 = Key.newBuilder().setThresholdKey(invalidThresholdKey).build();
        Key regKey2 = Key.newBuilder().setKeyList(invalidKeyList).build();

        return hapiTest(
                withOpContext((spec, opLog) -> {
                    spec.registry().saveKey("regKey1", regKey1);
                    spec.registry().saveKey("regKey2", regKey2);
                }),
                cryptoCreate("badThresholdKeyAccount")
                        .keyShape(thresholdShape)
                        .balance(initialBalance)
                        .logged()
                        .hasPrecheck(INVALID_ADMIN_KEY),
                cryptoCreate("badThresholdKeyAccount2")
                        .key("regKey1")
                        .balance(initialBalance)
                        .logged()
                        .signedBy(GENESIS)
                        .hasPrecheck(INVALID_ADMIN_KEY),
                cryptoCreate("badThresholdKeyAccount3")
                        .key("regKey2")
                        .balance(initialBalance)
                        .logged()
                        .signedBy(GENESIS)
                        .hasPrecheck(INVALID_ADMIN_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountInvalidNestedThresholdKey() {
        KeyShape goodShape = threshOf(2, 3);
        KeyShape thresholdShape0 = threshOf(0, SIMPLE, SIMPLE, SIMPLE);
        KeyShape thresholdShape4 = threshOf(4, SIMPLE, SIMPLE, SIMPLE);
        KeyShape badShape0 = threshOf(1, thresholdShape0, SIMPLE, SIMPLE);
        KeyShape badShape4 = threshOf(1, SIMPLE, thresholdShape4, SIMPLE);

        KeyShape shape0 = threshOf(3, badShape0, goodShape, goodShape, goodShape);
        KeyShape shape4 = threshOf(3, goodShape, badShape4, goodShape, goodShape);

        long initialBalance = 10_000L;

        return hapiTest(
                cryptoCreate(NO_KEYS)
                        .keyShape(shape0)
                        .balance(initialBalance)
                        .logged()
                        .hasPrecheck(INVALID_ADMIN_KEY),
                cryptoCreate(NO_KEYS)
                        .keyShape(shape4)
                        .balance(initialBalance)
                        .logged()
                        .hasPrecheck(INVALID_ADMIN_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountThresholdKeyWithInvalidThreshold() {
        KeyShape thresholdShape0 = threshOf(0, SIMPLE, SIMPLE, SIMPLE);
        KeyShape thresholdShape4 = threshOf(4, SIMPLE, SIMPLE, SIMPLE);

        long initialBalance = 10_000L;

        return hapiTest(
                cryptoCreate("badThresholdKeyAccount1")
                        .keyShape(thresholdShape0)
                        .balance(initialBalance)
                        .logged()
                        .hasPrecheck(INVALID_ADMIN_KEY),
                cryptoCreate("badThresholdKeyAccount2")
                        .keyShape(thresholdShape4)
                        .balance(initialBalance)
                        .logged()
                        .hasPrecheck(INVALID_ADMIN_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountInvalidED25519() {
        long initialBalance = 10_000L;
        Key emptyKey = Key.newBuilder().setEd25519(ByteString.EMPTY).build();
        Key shortKey =
                Key.newBuilder().setEd25519(ByteString.copyFrom(new byte[10])).build();
        return hapiTest(
                withOpContext((spec, opLog) -> {
                    spec.registry().saveKey(SHORT_KEY, shortKey);
                    spec.registry().saveKey(EMPTY_KEY_STRING, emptyKey);
                }),
                cryptoCreate(SHORT_KEY)
                        .key(SHORT_KEY)
                        .balance(initialBalance)
                        .signedBy(GENESIS)
                        .logged()
                        .hasPrecheck(INVALID_ADMIN_KEY),
                cryptoCreate(EMPTY_KEY_STRING)
                        .key(EMPTY_KEY_STRING)
                        .balance(initialBalance)
                        .signedBy(GENESIS)
                        .logged()
                        .hasPrecheck(BAD_ENCODING)
                // In modular code this error is thrown in handle, but it is fixed using dynamic property
                // spec.streamlinedIngestChecks
                // to accommodate error codes moved from Ingest to handle
                );
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithECDSAAlias() {
        return hapiTest(newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE), withOpContext((spec, opLog) -> {
            final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
            final var op = cryptoCreate(ACCOUNT)
                    .alias(ecdsaKey.toByteString())
                    .balance(100 * ONE_HBAR)
                    .hasPrecheck(INVALID_ALIAS_KEY);

            allRunFor(spec, op);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithED25519Alias() {
        return hapiTest(newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519), withOpContext((spec, opLog) -> {
            var ed25519Key = spec.registry().getKey(ED_25519_KEY);
            final var op = cryptoCreate(ACCOUNT)
                    .alias(ed25519Key.toByteString())
                    .balance(1000 * ONE_HBAR)
                    .hasPrecheck(INVALID_ALIAS_KEY);

            allRunFor(spec, op);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithECKeyAndNoAlias() {
        final var mirrorAddress = new AtomicReference<Address>();
        return hapiTest(newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE), withOpContext((spec, opLog) -> {
            final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
            final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
            final var addressBytes = recoverAddressFromPubKey(tmp);
            assert addressBytes.length > 0;
            final var evmAddressBytes = ByteString.copyFrom(addressBytes);
            final var createWithECDSAKey =
                    cryptoCreate(ACCOUNT).key(SECP_256K1_SOURCE_KEY).exposingEvmAddressTo(mirrorAddress::set);
            final var getAccountInfo = sourcing(() -> getAccountInfo(ACCOUNT)
                    .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias().evmAddress(mirrorAddress.get())));

            final var getECDSAAliasAccountInfo =
                    getAliasedAccountInfo(ecdsaKey.toByteString()).hasCostAnswerPrecheck(INVALID_ACCOUNT_ID);

            final var getEVMAddressAliasAccountInfo =
                    getAliasedAccountInfo(evmAddressBytes).hasCostAnswerPrecheck(INVALID_ACCOUNT_ID);

            final var createWithECDSAKeyAlias = cryptoCreate(ANOTHER_ACCOUNT)
                    .alias(ecdsaKey.toByteString())
                    .hasPrecheck(INVALID_ALIAS_KEY)
                    .balance(100 * ONE_HBAR);

            final var createWithEVMAddressAlias = cryptoCreate(ANOTHER_ACCOUNT)
                    .alias(evmAddressBytes)
                    .hasPrecheck(INVALID_ALIAS_KEY)
                    .balance(100 * ONE_HBAR);

            allRunFor(
                    spec,
                    createWithECDSAKey,
                    getAccountInfo,
                    getECDSAAliasAccountInfo,
                    getEVMAddressAliasAccountInfo,
                    createWithECDSAKeyAlias,
                    createWithEVMAddressAlias);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithEDKeyAndNoAlias() {
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                cryptoCreate(ACCOUNT).key(ED_25519_KEY),
                getAccountInfo(ACCOUNT).has(accountWith().key(ED_25519_KEY).noAlias()));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithED25519KeyAndED25519Alias() {
        return hapiTest(newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519), withOpContext((spec, opLog) -> {
            var ed25519Key = spec.registry().getKey(ED_25519_KEY);
            final var op = cryptoCreate(ACCOUNT)
                    .key(ED_25519_KEY)
                    .alias(ed25519Key.toByteString())
                    .balance(1000 * ONE_HBAR)
                    .hasPrecheck(INVALID_ALIAS_KEY);

            allRunFor(spec, op);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithECKeyAndECKeyAlias() {
        return hapiTest(newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE), withOpContext((spec, opLog) -> {
            final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
            final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
            final var addressBytes = recoverAddressFromPubKey(tmp);
            assert addressBytes.length > 0;
            final var evmAddressBytes = ByteString.copyFrom(addressBytes);

            final var op = cryptoCreate(ACCOUNT)
                    .key(SECP_256K1_SOURCE_KEY)
                    .alias(ecdsaKey.toByteString())
                    .balance(100 * ONE_HBAR)
                    .hasPrecheck(INVALID_ALIAS_KEY);
            final var op2 =
                    cryptoCreate(ANOTHER_ACCOUNT).key(SECP_256K1_SOURCE_KEY).balance(100 * ONE_HBAR);
            final var op3 = cryptoCreate(ACCOUNT)
                    .alias(evmAddressBytes)
                    .hasPrecheck(INVALID_ALIAS_KEY)
                    .balance(100 * ONE_HBAR);

            allRunFor(spec, op, op2, op3);
            var hapiGetAnotherAccountInfo = getAccountInfo(ANOTHER_ACCOUNT)
                    .has(accountWith()
                            .key(SECP_256K1_SOURCE_KEY)
                            .noAlias()
                            .autoRenew(THREE_MONTHS_IN_SECONDS)
                            .receiverSigReq(false));
            allRunFor(spec, hapiGetAnotherAccountInfo);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithECDSAKeyAliasDifferentThanAdminKeyShouldFail() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ED_KEY).shape(ED25519),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var op =
                            // try to create without signature for the alias
                            cryptoCreate(ACCOUNT)
                                    .key(ED_KEY)
                                    .alias(ecdsaKey.toByteString())
                                    .balance(100 * ONE_HBAR)
                                    .hasPrecheck(INVALID_ALIAS_KEY);
                    allRunFor(spec, op);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithEVMAddressAliasFromSameKey() {
        final var edKey = "edKey";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(edKey).shape(ED25519),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes.length > 0;
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);

                    final var op = cryptoCreate(ACCOUNT)
                            .key(SECP_256K1_SOURCE_KEY)
                            .alias(evmAddressBytes)
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .balance(100 * ONE_HBAR);
                    final var op2 = cryptoCreate(ACCOUNT)
                            .key(SECP_256K1_SOURCE_KEY)
                            .alias(evmAddressBytes)
                            .balance(100 * ONE_HBAR)
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasPrecheck(ALIAS_ALREADY_ASSIGNED);
                    final var op3 = cryptoCreate(ACCOUNT)
                            .key(edKey)
                            .alias(evmAddressBytes)
                            .balance(100 * ONE_HBAR)
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasPrecheck(ALIAS_ALREADY_ASSIGNED);
                    allRunFor(spec, op, op2, op3);
                    var hapiGetAccountInfo = getAccountInfo(ACCOUNT)
                            .has(accountWith()
                                    .key(SECP_256K1_SOURCE_KEY)
                                    .evmAddress(evmAddressBytes)
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false))
                            .logged();
                    allRunFor(spec, hapiGetAccountInfo);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithEVMAddressAliasFromDifferentKey() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ED_KEY).shape(ED25519),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes.length > 0;
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);
                    final var op =
                            // try to create without signature for the alias
                            cryptoCreate(ACCOUNT)
                                    .key(ED_KEY)
                                    .alias(evmAddressBytes)
                                    .balance(100 * ONE_HBAR)
                                    .hasKnownStatus(INVALID_SIGNATURE);
                    final var op2 =
                            // create with proper signatures
                            cryptoCreate(ACCOUNT)
                                    .key(ED_KEY)
                                    .alias(evmAddressBytes)
                                    .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                                    .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                                    .balance(100 * ONE_HBAR);
                    allRunFor(spec, op, op2);
                    var hapiGetAccountInfo = getAccountInfo(ACCOUNT)
                            .has(accountWith()
                                    .key(ED_KEY)
                                    .evmAddress(evmAddressBytes)
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false))
                            .logged();
                    allRunFor(spec, hapiGetAccountInfo);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithEDKeyAliasDifferentThanAdminKeyShouldFail() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ED_KEY).shape(ED25519),
                withOpContext((spec, opLog) -> {
                    final var edKey = spec.registry().getKey(ED_KEY);
                    final var op =
                            // try to create without signature for the alias
                            cryptoCreate(ACCOUNT)
                                    .key(SECP_256K1_SOURCE_KEY)
                                    .alias(edKey.toByteString())
                                    .balance(100 * ONE_HBAR)
                                    .hasPrecheck(INVALID_ALIAS_KEY);
                    allRunFor(spec, op);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> successfullyRecreateAccountWithSameAliasAfterDeletion() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var addressBytes = recoverAddressFromPubKey(
                            ecdsaKey.getECDSASecp256K1().toByteArray());
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);

                    final var op1 = cryptoCreate(ACCOUNT)
                            .key(ED_25519_KEY)
                            .alias(evmAddressBytes)
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .balance(ONE_HBAR);

                    final var op2 = cryptoDelete(ACCOUNT);

                    final var op3 = cryptoCreate(ACCOUNT)
                            .key(ED_25519_KEY)
                            .alias(evmAddressBytes)
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .balance(ONE_HBAR);

                    allRunFor(spec, op1, op2, op3);
                }),
                getAccountInfo(ACCOUNT).has(accountWith().balance(ONE_HBAR)));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithNoMaxAutoAssocAndBalance() {
        double v13PriceUsd = 0.05;

        final var noAutoAssocSlots = "noAutoAssocSlots";

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ED_KEY).shape(ED25519),
                cryptoCreate(ED_KEY).balance(ONE_HUNDRED_HBARS),
                getAccountBalance(ED_KEY).hasTinyBars(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes.length > 0;
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);
                    final var op = cryptoCreate("noAutoAssoc")
                            .key(ED_KEY)
                            .via(noAutoAssocSlots)
                            .blankMemo()
                            .payingWith(ED_KEY);

                    final var op2 =
                            cryptoCreate(ACCOUNT).key(SECP_256K1_SOURCE_KEY).alias(evmAddressBytes);
                    allRunFor(spec, op, op2);
                }),
                validateChargedUsd(noAutoAssocSlots, v13PriceUsd),
                getAccountInfo("noAutoAssoc")
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .hasMaxAutomaticAssociations(0),
                getAccountInfo(ACCOUNT).hasAlreadyUsedAutomaticAssociations(0).hasMaxAutomaticAssociations(0));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithNegativeMaxAutoAssocAndBalance() {
        double v13PriceUsd = 0.05;
        final var negativeAutoAssocSlots = "negativeAutoAssocSlots";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ED_KEY).shape(ED25519),
                cryptoCreate(ED_KEY).balance(ONE_HUNDRED_HBARS),
                getAccountBalance(ED_KEY).hasTinyBars(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes.length > 0;
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);
                    final var op = cryptoCreate("negativeAutoAssoc")
                            .key(ED_KEY)
                            .maxAutomaticTokenAssociations(-1)
                            .via(negativeAutoAssocSlots)
                            .blankMemo()
                            .payingWith(ED_KEY);

                    final var op2 = cryptoCreate(ACCOUNT)
                            .key(SECP_256K1_SOURCE_KEY)
                            .maxAutomaticTokenAssociations(-1)
                            .alias(evmAddressBytes);
                    allRunFor(spec, op, op2);
                }),
                validateChargedUsd(negativeAutoAssocSlots, v13PriceUsd),
                getAccountInfo("negativeAutoAssoc")
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .hasMaxAutomaticAssociations(-1),
                getAccountInfo(ACCOUNT).hasAlreadyUsedAutomaticAssociations(0).hasMaxAutomaticAssociations(-1));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithInvalidNegativeMaxAutoAssoc() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ED_KEY).shape(ED25519),
                cryptoCreate(ED_KEY).balance(ONE_HUNDRED_HBARS),
                getAccountBalance(ED_KEY).hasTinyBars(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes.length > 0;
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);
                    final var op = cryptoCreate("negativeAutoAssoc")
                            .key(ED_KEY)
                            .maxAutomaticTokenAssociations(-2)
                            .blankMemo()
                            .payingWith(ED_KEY)
                            .hasPrecheck(INVALID_MAX_AUTO_ASSOCIATIONS)
                            .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS);

                    final var op2 = cryptoCreate(ACCOUNT)
                            .key(SECP_256K1_SOURCE_KEY)
                            .maxAutomaticTokenAssociations(-2)
                            .alias(evmAddressBytes)
                            .hasPrecheck(INVALID_MAX_AUTO_ASSOCIATIONS)
                            .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS);
                    allRunFor(spec, op, op2);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithZeroMaxAssoc() {
        double v13PriceUsd = 0.05;

        final var noAutoAssocSlots = "noAutoAssocSlots";

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ED_KEY).shape(ED25519),
                cryptoCreate(ED_KEY).balance(ONE_HUNDRED_HBARS),
                getAccountBalance(ED_KEY).hasTinyBars(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes.length > 0;
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);
                    final var op = cryptoCreate("noAutoAssoc")
                            .key(ED_KEY)
                            .maxAutomaticTokenAssociations(0)
                            .via(noAutoAssocSlots)
                            .blankMemo()
                            .payingWith(ED_KEY);

                    final var op2 = cryptoCreate(ACCOUNT)
                            .key(SECP_256K1_SOURCE_KEY)
                            .maxAutomaticTokenAssociations(0)
                            .alias(evmAddressBytes);
                    allRunFor(spec, op, op2);
                }),
                validateChargedUsd(noAutoAssocSlots, v13PriceUsd),
                getAccountInfo("noAutoAssoc")
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .hasMaxAutomaticAssociations(0),
                getAccountInfo(ACCOUNT).hasAlreadyUsedAutomaticAssociations(0).hasMaxAutomaticAssociations(0));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWith1001MaxAssoc() {
        int operationCount = 11;
        // int operationCount = 1001;
        HapiSpecOperation[] operations1001 = new HapiSpecOperation[operationCount + 1];
        for (int i = 0; i < operationCount; i++) {
            final int index = i;
            operations1001[i] = withOpContext((spec, assertLog) -> {
                String tokenName = "token" + index;
                HapiTokenCreate op = tokenCreate(tokenName)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000)
                        .treasury(TOKEN_TREASURY)
                        .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                        .hasKnownStatusFrom(STANDARD_PERMISSIBLE_OUTCOMES);

                HapiCryptoTransfer op2 = cryptoTransfer(moving(10, tokenName).between(TOKEN_TREASURY, ACCOUNT));

                allRunFor(spec, op, op2);
            });
        }
        // assertion here
        operations1001[operationCount] = getAccountInfo(ACCOUNT)
                .hasAlreadyUsedAutomaticAssociations(operationCount)
                .hasMaxAutomaticAssociations(operationCount);

        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes.length > 0;
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);

                    final var op = cryptoCreate(ACCOUNT)
                            .key(SECP_256K1_SOURCE_KEY)
                            .maxAutomaticTokenAssociations(operationCount)
                            .alias(evmAddressBytes);
                    allRunFor(spec, op);
                }),
                operations1001));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithEVMAddressAliasAndECKey() {
        return hapiTest(newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE), withOpContext((spec, opLog) -> {
            final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
            final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
            final var addressBytes = recoverAddressFromPubKey(tmp);
            assert addressBytes.length > 0;
            final var evmAddressBytes = ByteString.copyFrom(addressBytes);
            final var op = cryptoCreate(ACCOUNT)
                    .key(SECP_256K1_SOURCE_KEY)
                    .alias(evmAddressBytes)
                    .balance(100 * ONE_HBAR)
                    .via("createTxn");
            final var op2 = cryptoCreate(ACCOUNT)
                    .alias(ecdsaKey.toByteString())
                    .hasPrecheck(INVALID_ALIAS_KEY)
                    .balance(100 * ONE_HBAR);
            final var op3 = cryptoCreate(ACCOUNT)
                    .alias(evmAddressBytes)
                    .hasPrecheck(INVALID_ALIAS_KEY)
                    .balance(100 * ONE_HBAR);
            final var op4 =
                    cryptoCreate(ANOTHER_ACCOUNT).key(SECP_256K1_SOURCE_KEY).balance(100 * ONE_HBAR);
            final var op5 = cryptoCreate(ACCOUNT)
                    .key(SECP_256K1_SOURCE_KEY)
                    .alias(ByteString.copyFromUtf8("Invalid alias"))
                    .hasPrecheck(INVALID_ALIAS_KEY)
                    .balance(100 * ONE_HBAR);
            final var op6 = cryptoCreate(ACCOUNT)
                    .key(SECP_256K1_SOURCE_KEY)
                    .alias(evmAddressBytes)
                    .balance(100 * ONE_HBAR)
                    .hasPrecheck(ALIAS_ALREADY_ASSIGNED);

            allRunFor(spec, op, op2, op3, op4, op5, op6);
            var hapiGetAccountInfo = getAliasedAccountInfo(evmAddressBytes)
                    .has(accountWith()
                            .key(SECP_256K1_SOURCE_KEY)
                            .autoRenew(THREE_MONTHS_IN_SECONDS)
                            .receiverSigReq(false));
            var hapiGetAnotherAccountInfo = getAccountInfo(ANOTHER_ACCOUNT)
                    .has(accountWith()
                            .key(SECP_256K1_SOURCE_KEY)
                            .noAlias()
                            .autoRenew(THREE_MONTHS_IN_SECONDS)
                            .receiverSigReq(false));
            final var getTxnRecord =
                    getTxnRecord("createTxn").hasPriority(recordWith().hasNoAlias());
            allRunFor(spec, hapiGetAccountInfo, hapiGetAnotherAccountInfo, getTxnRecord);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithEVMAddress() {
        return hapiTest(newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE), withOpContext((spec, opLog) -> {
            final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
            final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
            final var addressBytes = recoverAddressFromPubKey(tmp);
            assert addressBytes.length > 0;
            final var evmAddressBytes = ByteString.copyFrom(addressBytes);
            final var op = cryptoCreate(ACCOUNT)
                    .alias(evmAddressBytes)
                    .balance(100 * ONE_HBAR)
                    .hasPrecheck(INVALID_ALIAS_KEY);
            allRunFor(spec, op);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> accountsWithDifferentShardOrRealmNotCreated() {
        final String key = "key";
        return hapiTest(
                newKeyNamed(key),
                cryptoCreate("control").key(key).balance(1L).hasKnownStatus(SUCCESS),
                cryptoCreate("differentShard")
                        .key(key)
                        .balance(1L)
                        .shardId(ShardID.newBuilder().setShardNum(1).build())
                        .hasKnownStatus(INVALID_ACCOUNT_ID),
                cryptoCreate("differentRealm")
                        .key(key)
                        .balance(1L)
                        .realmId(RealmID.newBuilder().setRealmNum(1).build())
                        .hasKnownStatus(INVALID_ACCOUNT_ID));
    }
}
