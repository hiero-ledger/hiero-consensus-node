// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.entities.SpecKey.Type.SECP_256K1;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FREEZE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.KYC_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.WIPE_KEY;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.contractIdKeyFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUppercase;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NAME_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_SYMBOL_TOO_LONG;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.Key;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecKey;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("updateToken")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
public class UpdateTokenPrecompileTest {
    private static final long GAS_NEEDED = 1_000_000L;
    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);
    // Name under which AuthorizeContractOperation registers the key it gives the contract for the shared token
    private static final String SHARED_TOKEN_MANAGED_KEY = "sharedAuthorizedToken_UpdateTokenKeysContractManagedKey";
    private static final String HTS_ADDRESS = "0x0000000000000000000000000000000000000167";
    private static final String KEYLESS_TOKEN = "keylessToken";
    private static final String KEYLESS_TREASURY = "keylessTreasury";
    private static final String KEYLESS_ETH_SENDER_KEY = "keylessEthSenderKey";
    private static final String KEYLESS_NEW_ADMIN_KEY = "keylessNewAdminKey";
    private static final String KEYLESS_UPDATE_TXN = "keylessUpdateTxn";

    @Contract(contract = "UpdateTokenInfoContract", creationGas = 4_000_000L)
    static SpecContract updateTokenContract;

    // Forwards an arbitrary TokenKey[] to updateTokenKeys at 0x167, reverting unless the precompile returns SUCCESS
    @Contract(contract = "UpdateTokenKeysContract", creationGas = 1_000_000L)
    static SpecContract updateTokenKeysContract;

    @FungibleToken(
            name = "immutableToken",
            keys = {FEE_SCHEDULE_KEY, SUPPLY_KEY, WIPE_KEY, PAUSE_KEY, FREEZE_KEY, KYC_KEY})
    static SpecFungibleToken immutableToken;

    // Admin key handed to updateKeysCaller once in beforeAll; only used by tests that never change its keys
    @FungibleToken(name = "sharedAuthorizedToken")
    static SpecFungibleToken sharedAuthorizedToken;

    @Key
    static SpecKey ed25519Key;

    @Key(type = SECP_256K1)
    static SpecKey secp256k1Key;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(sharedAuthorizedToken.authorizeContracts(updateTokenKeysContract));
    }

    @HapiTest
    @DisplayName("cannot update a missing token")
    public Stream<DynamicTest> cannotUpdateMissingToken() {
        return hapiTest(updateTokenContract
                .call("tokenUpdateKeys", ZERO_ADDRESS, ed25519Key, secp256k1Key, updateTokenContract)
                .gas(GAS_NEEDED)
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)));
    }

    @HapiTest
    @DisplayName("cannot update an immutable token")
    public Stream<DynamicTest> cannotUpdateImmutableToken(@Account final SpecAccount newTreasury) {
        return hapiTest(
                newTreasury.authorizeContract(updateTokenContract),
                updateTokenContract
                        .call("updateTokenTreasury", immutableToken, newTreasury)
                        .gas(GAS_NEEDED)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_IS_IMMUTABLE)),
                updateTokenContract
                        .call("tokenUpdateKeys", immutableToken, ed25519Key, secp256k1Key, updateTokenContract)
                        .gas(GAS_NEEDED)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_IS_IMMUTABLE)));
    }

    @HapiTest
    @DisplayName("cannot update the keys of a token that has no keys at all, calling HTS directly")
    public Stream<DynamicTest> cannotUpdateKeysOfKeylessTokenViaDirectEthereumCall() {
        return hapiTest(
                newKeyNamed(KEYLESS_ETH_SENDER_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(KEYLESS_NEW_ADMIN_KEY),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, KEYLESS_ETH_SENDER_KEY, ONE_HUNDRED_HBARS)),
                cryptoCreate(KEYLESS_TREASURY),
                // Created with no keys of any kind (so it is immutable)
                tokenCreate(KEYLESS_TOKEN).treasury(KEYLESS_TREASURY),
                withOpContext((spec, _) -> {
                    final var token =
                            asHeadlongAddress(asAddress(spec.registry().getTokenID(KEYLESS_TOKEN)));
                    final var newAdminKey = spec.registry()
                            .getKey(KEYLESS_NEW_ADMIN_KEY)
                            .getEd25519()
                            .toByteArray();
                    final var adminKeyEntry = Tuple.of(
                            BigInteger.ONE, Tuple.of(false, ZERO_ADDRESS, newAdminKey, new byte[0], ZERO_ADDRESS));
                    allRunFor(
                            spec,
                            // Straight to the HTS system contract at 0x167, with no intermediary contract
                            ethereumCallWithFunctionAbi(
                                            false,
                                            HTS_ADDRESS,
                                            getABIFor(FUNCTION, "updateTokenKeys", "IHederaTokenService"),
                                            token,
                                            new Tuple[] {adminKeyEntry})
                                    .signingWith(KEYLESS_ETH_SENDER_KEY)
                                    .payingWith(GENESIS)
                                    .gasLimit(GAS_NEEDED)
                                    .via(KEYLESS_UPDATE_TXN),
                            getTxnRecord(KEYLESS_UPDATE_TXN)
                                    .andAllChildRecords()
                                    .hasChildRecords(recordWith().status(TOKEN_IS_IMMUTABLE)));
                }),
                // The rejected update must not have set any key
                getTokenInfo(KEYLESS_TOKEN)
                        .hasEmptyAdminKey()
                        .hasEmptyKycKey()
                        .hasEmptyFreezeKey()
                        .hasEmptyWipeKey()
                        .hasEmptySupplyKey()
                        .hasEmptyFeeScheduleKey()
                        .hasEmptyPauseKey());
    }

    @HapiTest
    @DisplayName("updateTokenKeys with duplicate keyTypes from an authorized contract applies them in order")
    public Stream<DynamicTest> duplicateKeyTypesFromAuthorizedContractApplyInOrder(
            @FungibleToken final SpecFungibleToken token) {
        final var supplyKeyType = BigInteger.valueOf(0x10);
        return hapiTest(
                newKeyNamed("dupContractFirstSupply"),
                newKeyNamed("dupContractSecondSupply"),
                token.authorizeContracts(updateTokenKeysContract),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        updateTokenKeysContract
                                .call("updateTokenKeys", token, new Tuple[] {
                                    keyEntry(supplyKeyType, ed25519Of(spec, "dupContractFirstSupply")),
                                    keyEntry(supplyKeyType, ed25519Of(spec, "dupContractSecondSupply"))
                                })
                                .gas(GAS_NEEDED))),
                // No conflict error: the last entry for the slot wins
                token.getInfo().andAssert(info -> info.searchKeysGlobally().hasSupplyKey("dupContractSecondSupply")));
    }

    @HapiTest
    @DisplayName("updateTokenKeys ignores an undefined bit next to a defined one for an authorized contract")
    public Stream<DynamicTest> strayUndefinedBitNextToAdminBitIsAppliedAsAdminKey(
            @FungibleToken final SpecFungibleToken token) {
        return hapiTest(
                token.authorizeContracts(updateTokenKeysContract),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        updateTokenKeysContract
                                .call(
                                        "updateTokenKeys",
                                        token,
                                        // 0x101 = admin (0x01) plus the undefined 0x100; the new admin key is the
                                        // calling contract itself, so no extra signature is needed
                                        new Tuple[] {
                                            Tuple.of(
                                                    BigInteger.valueOf(0x101),
                                                    Tuple.of(
                                                            false,
                                                            updateTokenKeysContract.addressOn(
                                                                    spec.targetNetworkOrThrow()),
                                                            new byte[0],
                                                            new byte[0],
                                                            ZERO_ADDRESS))
                                        })
                                .gas(GAS_NEEDED))),
                updateTokenKeysContract.doWith(
                        contract -> token.getInfo().andAssert(info -> info.hasAdminKey(contractIdKeyFor(contract)))));
    }

    @HapiTest
    @DisplayName(
            "updateTokenKeys with an empty key list from an authorized contract reports success and changes nothing")
    public Stream<DynamicTest> emptyKeyListFromAuthorizedContractChangesNothing() {
        return authorizedContractUpdateKeepsAllKeys("emptyKeyList", _ -> new Tuple[0]);
    }

    @HapiTest
    @DisplayName("updateTokenKeys with keyType zero from an authorized contract reports success and changes nothing")
    public Stream<DynamicTest> zeroKeyTypeFromAuthorizedContractChangesNothing() {
        return authorizedContractUpdateKeepsAllKeys(
                "zeroKeyType", newKeys -> new Tuple[] {keyEntry(BigInteger.ZERO, newKeys[0])});
    }

    @HapiTest
    @DisplayName(
            "updateTokenKeys with an undefined bit from an authorized contract reports success and changes nothing")
    public Stream<DynamicTest> undefinedBitFromAuthorizedContractChangesNothing() {
        return authorizedContractUpdateKeepsAllKeys(
                "undefinedBit", newKeys -> new Tuple[] {keyEntry(BigInteger.valueOf(0x100), newKeys[0])});
    }

    @HapiTest
    @DisplayName("updateTokenKeys from a contract that holds none of the keys is unauthorized")
    public Stream<DynamicTest> validEntryFromUnauthorizedContractIsRejected(
            @FungibleToken final SpecFungibleToken token) {
        return hapiTest(
                newKeyNamed("unauthorizedNewSupply"),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        updateTokenKeysContract
                                .call("updateTokenKeys", token, new Tuple[] {
                                    keyEntry(BigInteger.valueOf(0x10), ed25519Of(spec, "unauthorizedNewSupply"))
                                })
                                .gas(GAS_NEEDED)
                                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_SIGNATURE)))),
                token.getInfo().andAssert(info -> info.hasSupplyKey(token.name())));
    }

    /**
     * The contract holds the token's admin key, so authorization is not in play: the call succeeds (the contract
     * reverts on any status but SUCCESS) and none of the token's keys may change.
     */
    private Stream<DynamicTest> authorizedContractUpdateKeepsAllKeys(
            @NonNull final String tag, @NonNull final Function<byte[][], Tuple[]> entries) {
        final var newKey = tag + "NewKey";
        return hapiTest(
                newKeyNamed(newKey),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        updateTokenKeysContract
                                .call("updateTokenKeys", sharedAuthorizedToken, entries.apply(new byte[][] {
                                    ed25519Of(spec, newKey)
                                }))
                                .gas(GAS_NEEDED))),
                // Role keys are looked up by token name: the supply key is the original one, the rest were never set
                sharedAuthorizedToken
                        .getInfo()
                        .andAssert(info -> info.hasSupplyKey(sharedAuthorizedToken.name())
                                .hasEmptyKycKey()
                                .hasEmptyFreezeKey()
                                .hasEmptyWipeKey()
                                .hasEmptyFeeScheduleKey()
                                .hasEmptyPauseKey()
                                .hasEmptyMetadataKey()),
                // searchKeysGlobally() applies to the whole query, so the admin key needs its own query: it is
                // still the key authorizeContracts() gave the contract (registered under this name)
                sharedAuthorizedToken
                        .getInfo()
                        .andAssert(info -> info.searchKeysGlobally().hasAdminKey(SHARED_TOKEN_MANAGED_KEY)));
    }

    private static byte[] ed25519Of(@NonNull final HapiSpec spec, @NonNull final String keyName) {
        return spec.registry().getKey(keyName).getEd25519().toByteArray();
    }

    private static Tuple keyEntry(@NonNull final BigInteger keyType, @NonNull final byte[] ed25519) {
        return Tuple.of(keyType, Tuple.of(false, ZERO_ADDRESS, ed25519, new byte[0], ZERO_ADDRESS));
    }

    @HapiTest
    @DisplayName("can only update a mutable token treasury if authorized")
    public Stream<DynamicTest> canUpdateMutableTokenTreasuryOnceAuthorized(
            @Account final SpecAccount newTreasury,
            @NonFungibleToken(
                            numPreMints = 1,
                            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY})
                    final SpecNonFungibleToken mutableToken) {
        return hapiTest(
                // First confirm we CANNOT update the treasury without authorization
                updateTokenContract
                        .call("updateTokenTreasury", mutableToken, newTreasury)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_SIGNATURE)),

                // So authorize the contract to manage both the token and the new treasury
                newTreasury.authorizeContract(updateTokenContract),
                mutableToken.authorizeContracts(updateTokenContract),
                // Also need to associate the token with the new treasury
                newTreasury.associateTokens(mutableToken),

                // Now do a contract-managed treasury update
                updateTokenContract.call("updateTokenTreasury", mutableToken, newTreasury),
                // And verify a treasury-owned NFT has the new treasury as its owner
                mutableToken.serialNo(1).assertOwnerIs(newTreasury));
    }

    @Nested
    @DisplayName("once authorized")
    class OnceAuthorized {
        @Contract(contract = "TokenInfoSingularUpdate", creationGas = 4_000_000L)
        static SpecContract updateTokenPropertyContract;

        @Account(name = "sharedTreasury")
        static SpecAccount sharedTreasury;

        @NonFungibleToken(
                numPreMints = 1,
                useAutoRenewAccount = true,
                keys = {
                    ADMIN_KEY,
                    FEE_SCHEDULE_KEY,
                    SUPPLY_KEY,
                    WIPE_KEY,
                    PAUSE_KEY,
                    FREEZE_KEY,
                    KYC_KEY,
                })
        static SpecNonFungibleToken sharedMutableToken;

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
            sharedMutableToken.setTreasury(sharedTreasury);
            testLifecycle.doAdhoc(
                    sharedTreasury.authorizeContract(updateTokenContract),
                    sharedMutableToken.authorizeContracts(updateTokenContract, updateTokenPropertyContract));
        }

        @HapiTest
        @DisplayName("can update the name")
        public Stream<DynamicTest> canUpdateName() {
            return hapiTest(
                    updateTokenPropertyContract.call("updateTokenName", sharedMutableToken, "NEW_NAME"),
                    sharedMutableToken.getInfo().andAssert(info -> info.hasName("NEW_NAME")));
        }

        @HapiTest
        @DisplayName("can update the symbol")
        public Stream<DynamicTest> canUpdateSymbol() {
            return hapiTest(
                    updateTokenPropertyContract.call("updateTokenSymbol", sharedMutableToken, "NSYM"),
                    sharedMutableToken.getInfo().andAssert(info -> info.hasSymbol("NSYM")));
        }

        @HapiTest
        @DisplayName("can update the memo")
        public Stream<DynamicTest> canUpdateMemo() {
            return hapiTest(
                    updateTokenPropertyContract.call("updateTokenMemo", sharedMutableToken, "Did you get it?"),
                    sharedMutableToken.getInfo().andAssert(info -> info.hasEntityMemo("Did you get it?")));
        }

        @HapiTest
        @DisplayName("can update the auto-renew account")
        public Stream<DynamicTest> canUpdateAutoRenewAccount(
                @Account(name = "autoRenewAccount") SpecAccount autoRenewAccount) {
            return hapiTest(
                    autoRenewAccount.authorizeContract(updateTokenPropertyContract),
                    updateTokenPropertyContract.call(
                            "updateTokenAutoRenewAccount", sharedMutableToken, autoRenewAccount),
                    sharedMutableToken.getInfo().andAssert(info -> info.hasAutoRenewAccount(autoRenewAccount.name())));
        }

        @HapiTest
        @DisplayName("can update the auto-renew period")
        public Stream<DynamicTest> canUpdateAutoRenewPeriod() {
            final var abbreviatedPeriod = THREE_MONTHS_IN_SECONDS - 1234L;
            return hapiTest(
                    updateTokenPropertyContract.call(
                            "updateTokenAutoRenewPeriod", sharedMutableToken, abbreviatedPeriod),
                    sharedMutableToken.getInfo().andAssert(info -> info.hasAutoRenewPeriod(abbreviatedPeriod)));
        }

        @HapiTest
        @DisplayName("can update the admin key to itself")
        public Stream<DynamicTest> canUpdateAdminKeyToItself(@FungibleToken final SpecFungibleToken token) {
            return hapiTest(
                    token.authorizeContracts(updateTokenPropertyContract),
                    // This contract uses 0 as shorthand for the admin key
                    updateTokenPropertyContract.call("updateTokenKeyContractId", token, updateTokenPropertyContract, 0),
                    updateTokenPropertyContract.doWith(contract ->
                            token.getInfo().andAssert(info -> info.hasAdminKey(contractIdKeyFor(contract)))));
        }

        @HapiTest
        @DisplayName("can update the KYC key to a different Ed25519 key or itself")
        public Stream<DynamicTest> canUpdateKycKeyToEd25519OrItself() {
            return hapiTest(
                    // This contract uses 1 as shorthand for the KYC key
                    updateTokenPropertyContract.call("updateTokenKeyEd", sharedMutableToken, ed25519Key, 1),
                    ed25519Key.doWith(key -> sharedMutableToken.getInfo().andAssert(info -> info.hasKycKey(key))),
                    // And then to the managing contract's id
                    updateTokenPropertyContract.call(
                            "updateTokenKeyContractId", sharedMutableToken, updateTokenPropertyContract, 1),
                    updateTokenPropertyContract.doWith(contract -> sharedMutableToken
                            .getInfo()
                            .andAssert(info -> info.hasKycKey(contractIdKeyFor(contract)))));
        }

        @HapiTest
        @DisplayName("can update the freeze key to a different Ed25519 key or itself")
        public Stream<DynamicTest> canUpdateFreezeKeyToEd25519OrItself() {
            return hapiTest(
                    // This contract uses 2 as shorthand for the freeze key
                    updateTokenPropertyContract.call("updateTokenKeyEd", sharedMutableToken, ed25519Key, 2),
                    ed25519Key.doWith(key -> sharedMutableToken.getInfo().andAssert(info -> info.hasFreezeKey(key))),
                    // And then to the managing contract's id
                    updateTokenPropertyContract.call(
                            "updateTokenKeyContractId", sharedMutableToken, updateTokenPropertyContract, 2),
                    updateTokenPropertyContract.doWith(contract -> sharedMutableToken
                            .getInfo()
                            .andAssert(info -> info.hasFreezeKey(contractIdKeyFor(contract)))));
        }

        @HapiTest
        @DisplayName("can update the wipe key to a different Ed25519 key or itself")
        public Stream<DynamicTest> canUpdateWipeKeyToEd25519OrItself() {
            return hapiTest(
                    // This contract uses 3 as shorthand for the wipe key; first update to an Ed25519 key
                    updateTokenPropertyContract.call("updateTokenKeyEd", sharedMutableToken, ed25519Key, 3),
                    ed25519Key.doWith(key -> sharedMutableToken.getInfo().andAssert(info -> info.hasWipeKey(key))),
                    // And then to the managing contract's id
                    updateTokenPropertyContract.call(
                            "updateTokenKeyContractId", sharedMutableToken, updateTokenPropertyContract, 3),
                    updateTokenPropertyContract.doWith(contract -> sharedMutableToken
                            .getInfo()
                            .andAssert(info -> info.hasWipeKey(contractIdKeyFor(contract)))));
        }

        @HapiTest
        @DisplayName("can update the supply key to a different Ed25519 key or itself")
        public Stream<DynamicTest> canUpdateSupplyKeyToEd25519OrItself() {
            return hapiTest(
                    // This contract uses 4 as shorthand for the supply key; first update to an Ed25519 key
                    updateTokenPropertyContract.call("updateTokenKeyEd", sharedMutableToken, ed25519Key, 4),
                    ed25519Key.doWith(key -> sharedMutableToken.getInfo().andAssert(info -> info.hasSupplyKey(key))),
                    // And then to the managing contract's id
                    updateTokenPropertyContract.call(
                            "updateTokenKeyContractId", sharedMutableToken, updateTokenPropertyContract, 4),
                    updateTokenPropertyContract.doWith(contract -> sharedMutableToken
                            .getInfo()
                            .andAssert(info -> info.hasSupplyKey(contractIdKeyFor(contract)))));
        }

        @HapiTest
        @DisplayName("can update the fee schedule key to a different Ed25519 key or itself")
        public Stream<DynamicTest> canUpdateFeeScheduleKeyToEd25519OrItself() {
            return hapiTest(
                    // This contract uses 5 as shorthand for the fee schedule key; first update to an Ed25519 key
                    updateTokenPropertyContract.call("updateTokenKeyEd", sharedMutableToken, ed25519Key, 5),
                    ed25519Key.doWith(
                            key -> sharedMutableToken.getInfo().andAssert(info -> info.hasFeeScheduleKey(key))),
                    // And then to the managing contract's id
                    updateTokenPropertyContract.call(
                            "updateTokenKeyContractId", sharedMutableToken, updateTokenPropertyContract, 5),
                    updateTokenPropertyContract.doWith(contract -> sharedMutableToken
                            .getInfo()
                            .andAssert(info -> info.hasFeeScheduleKey(contractIdKeyFor(contract)))));
        }

        @HapiTest
        @DisplayName("can update the pause key to a different Ed25519 key or itself")
        public Stream<DynamicTest> canUpdatePauseKeyToEd25519OrItself() {
            return hapiTest(
                    // This contract uses 6 as shorthand for the pause key; first update to an Ed25519 key
                    updateTokenPropertyContract.call("updateTokenKeyEd", sharedMutableToken, ed25519Key, 6),
                    ed25519Key.doWith(key -> sharedMutableToken.getInfo().andAssert(info -> info.hasPauseKey(key))),
                    // And then to the managing contract's id
                    updateTokenPropertyContract.call(
                            "updateTokenKeyContractId", sharedMutableToken, updateTokenPropertyContract, 6),
                    updateTokenPropertyContract.doWith(contract -> sharedMutableToken
                            .getInfo()
                            .andAssert(info -> info.hasPauseKey(contractIdKeyFor(contract)))));
        }

        @Nested
        @DisplayName("still cannot")
        class StillCannot {
            @HapiTest
            @DisplayName("update to a new crypto admin key")
            public Stream<DynamicTest> cannotUpdateToCryptoAdminKey() {
                return hapiTest(
                        // This contract uses 0 as shorthand for the admin key
                        updateTokenPropertyContract
                                .call("updateTokenKeyEd", sharedMutableToken, ed25519Key, 0)
                                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_SIGNATURE)));
            }

            @HapiTest
            @DisplayName("exceed name and symbol length limits")
            public Stream<DynamicTest> enforcesNameAndSymbolLengthLimits() {
                return hapiTest(
                        // We cannot update with an overly long token name
                        updateTokenContract
                                .call(
                                        "checkNameAndSymbolLength",
                                        sharedMutableToken,
                                        sharedTreasury,
                                        randomUppercase(101),
                                        "SYMBOL")
                                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_NAME_TOO_LONG)),
                        // We cannot update with an overly long token symbol
                        updateTokenContract
                                .call(
                                        "checkNameAndSymbolLength",
                                        sharedMutableToken,
                                        sharedTreasury,
                                        "NAME",
                                        randomUppercase(101))
                                .andAssert(
                                        txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_SYMBOL_TOO_LONG)));
            }

            @HapiTest
            @DisplayName("add fee schedule key")
            public Stream<DynamicTest> cannotAddFeeScheduleKey(
                    @FungibleToken(keys = {ADMIN_KEY, SUPPLY_KEY, WIPE_KEY, PAUSE_KEY, FREEZE_KEY, KYC_KEY})
                            final SpecFungibleToken noFeeScheduleKeyToken) {
                noFeeScheduleKeyToken.setTreasury(sharedTreasury);
                return hapiTest(
                        noFeeScheduleKeyToken.authorizeContracts(updateTokenContract),
                        updateTokenContract
                                .call(
                                        "updateTokenWithKeys",
                                        noFeeScheduleKeyToken,
                                        sharedTreasury,
                                        ed25519Key,
                                        secp256k1Key,
                                        updateTokenContract)
                                .gas(GAS_NEEDED)
                                .andAssert(txn ->
                                        txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_FEE_SCHEDULE_KEY)));
            }

            @HapiTest
            @DisplayName("add supply key")
            public Stream<DynamicTest> cannotAddSupplyKey(
                    @FungibleToken(keys = {ADMIN_KEY, FEE_SCHEDULE_KEY, WIPE_KEY, PAUSE_KEY, FREEZE_KEY, KYC_KEY})
                            final SpecFungibleToken noSupplyKeyToken) {
                noSupplyKeyToken.setTreasury(sharedTreasury);
                return hapiTest(
                        noSupplyKeyToken.authorizeContracts(updateTokenContract),
                        updateTokenContract
                                .call(
                                        "updateTokenWithKeys",
                                        noSupplyKeyToken,
                                        sharedTreasury,
                                        ed25519Key,
                                        secp256k1Key,
                                        updateTokenContract)
                                .gas(GAS_NEEDED)
                                .andAssert(txn ->
                                        txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_SUPPLY_KEY)));
            }

            @HapiTest
            @DisplayName("add wipe key")
            public Stream<DynamicTest> cannotAddWipeKey(
                    @FungibleToken(keys = {ADMIN_KEY, FEE_SCHEDULE_KEY, SUPPLY_KEY, PAUSE_KEY, FREEZE_KEY, KYC_KEY})
                            final SpecFungibleToken noWipeKeyToken) {
                noWipeKeyToken.setTreasury(sharedTreasury);
                return hapiTest(
                        noWipeKeyToken.authorizeContracts(updateTokenContract),
                        updateTokenContract
                                .call(
                                        "updateTokenWithKeys",
                                        noWipeKeyToken,
                                        sharedTreasury,
                                        ed25519Key,
                                        secp256k1Key,
                                        updateTokenContract)
                                .gas(GAS_NEEDED)
                                .andAssert(
                                        txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_WIPE_KEY)));
            }

            @HapiTest
            @DisplayName("add pause key")
            public Stream<DynamicTest> cannotAddPauseKey(
                    @FungibleToken(keys = {ADMIN_KEY, FEE_SCHEDULE_KEY, SUPPLY_KEY, WIPE_KEY, FREEZE_KEY, KYC_KEY})
                            final SpecFungibleToken noPauseKeyToken) {
                noPauseKeyToken.setTreasury(sharedTreasury);
                return hapiTest(
                        noPauseKeyToken.authorizeContracts(updateTokenContract),
                        updateTokenContract
                                .call(
                                        "updateTokenWithKeys",
                                        noPauseKeyToken,
                                        sharedTreasury,
                                        ed25519Key,
                                        secp256k1Key,
                                        updateTokenContract)
                                .gas(GAS_NEEDED)
                                .andAssert(
                                        txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_PAUSE_KEY)));
            }

            @HapiTest
            @DisplayName("add freeze key")
            public Stream<DynamicTest> cannotAddFreezeKey(
                    @FungibleToken(keys = {ADMIN_KEY, FEE_SCHEDULE_KEY, SUPPLY_KEY, WIPE_KEY, PAUSE_KEY, KYC_KEY})
                            final SpecFungibleToken noFreezeKeyToken) {
                noFreezeKeyToken.setTreasury(sharedTreasury);
                return hapiTest(
                        noFreezeKeyToken.authorizeContracts(updateTokenContract),
                        updateTokenContract
                                .call(
                                        "updateTokenWithKeys",
                                        noFreezeKeyToken,
                                        sharedTreasury,
                                        ed25519Key,
                                        secp256k1Key,
                                        updateTokenContract)
                                .gas(GAS_NEEDED)
                                .andAssert(txn ->
                                        txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_FREEZE_KEY)));
            }

            @HapiTest
            @DisplayName("add kyc key")
            public Stream<DynamicTest> cannotAddKycKey(
                    @FungibleToken(keys = {ADMIN_KEY, FEE_SCHEDULE_KEY, SUPPLY_KEY, WIPE_KEY, PAUSE_KEY, FREEZE_KEY})
                            final SpecFungibleToken noKycKeyToken) {
                noKycKeyToken.setTreasury(sharedTreasury);
                return hapiTest(
                        noKycKeyToken.authorizeContracts(updateTokenContract),
                        updateTokenContract
                                .call(
                                        "updateTokenWithKeys",
                                        noKycKeyToken,
                                        sharedTreasury,
                                        ed25519Key,
                                        secp256k1Key,
                                        updateTokenContract)
                                .gas(GAS_NEEDED)
                                .andAssert(
                                        txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_KYC_KEY)));
            }

            @HapiTest
            @DisplayName("set invalid values")
            public Stream<DynamicTest> cannotSetInvalidValues(
                    @FungibleToken(
                                    keys = {
                                        ADMIN_KEY,
                                        FEE_SCHEDULE_KEY,
                                        SUPPLY_KEY,
                                        WIPE_KEY,
                                        PAUSE_KEY,
                                        FREEZE_KEY,
                                        KYC_KEY
                                    })
                            final SpecFungibleToken allKeysToken) {
                allKeysToken.setTreasury(sharedTreasury);
                return hapiTest(
                        allKeysToken.authorizeContracts(updateTokenContract),
                        updateTokenContract
                                .call(
                                        "updateTokenWithInvalidKeyValues",
                                        allKeysToken,
                                        sharedTreasury,
                                        THREE_MONTHS_IN_SECONDS)
                                .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
            }
        }
    }
}
