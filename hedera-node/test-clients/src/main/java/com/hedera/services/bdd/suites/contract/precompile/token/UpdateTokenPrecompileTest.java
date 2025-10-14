// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecKey.Type.SECP_256K1;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FREEZE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.KYC_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.WIPE_KEY;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.contractIdKeyFor;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUppercase;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
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
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
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
    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);

    @Contract(contract = "UpdateTokenInfoContract", creationGas = 4_000_000L)
    static SpecContract updateTokenContract;

    @FungibleToken(
            name = "immutableToken",
            keys = {FEE_SCHEDULE_KEY, SUPPLY_KEY, WIPE_KEY, PAUSE_KEY, FREEZE_KEY, KYC_KEY})
    static SpecFungibleToken immutableToken;

    @Key
    static SpecKey ed25519Key;

    @Key(type = SECP_256K1)
    static SpecKey secp256k1Key;

    @HapiTest
    @DisplayName("cannot update a missing token")
    public Stream<DynamicTest> cannotUpdateMissingToken() {
        return hapiTest(updateTokenContract
                .call("tokenUpdateKeys", ZERO_ADDRESS, ed25519Key, secp256k1Key, updateTokenContract)
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)));
    }

    @HapiTest
    @DisplayName("cannot update an immutable token")
    public Stream<DynamicTest> cannotUpdateImmutableToken(@Account final SpecAccount newTreasury) {
        return hapiTest(
                newTreasury.authorizeContract(updateTokenContract),
                updateTokenContract
                        .call("updateTokenTreasury", immutableToken, newTreasury)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_IS_IMMUTABLE)),
                updateTokenContract
                        .call("tokenUpdateKeys", immutableToken, ed25519Key, secp256k1Key, updateTokenContract)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_IS_IMMUTABLE)));
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
        @Tag(MATS)
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
        @Tag(MATS)
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
        @Tag(MATS)
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
