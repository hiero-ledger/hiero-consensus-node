// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression tests for empty-KeyList ({@code IMMUTABILITY_SENTINEL_KEY}) token keys.
 *
 * <p>An empty KeyList is accepted for any key at TokenCreate and stored verbatim, but it counts as
 * "no key" for that function: the privileged handlers treat an empty key as absent, so the function
 * (wipe, mint/burn, freeze, kyc, pause, fee schedule, metadata) is disabled and cannot be re-added
 * later — matching HIP-540's treatment of a removed key. An empty admin key likewise makes the token
 * immutable. Because it counts as "no key", creations that require a usable key — an NFT's supply
 * key, or the freeze key of a freeze-default token — reject the sentinel, and new associations
 * default to KYC-granted/unfrozen exactly as if the empty key were absent.
 *
 * <p>Crucially, an empty key must NOT be treated as "no signature required": the previous behavior
 * silently dropped the unsatisfiable signature requirement, making the operation permissionless.
 */
@Tag(TOKEN)
public class EmptyKeyListTokenKeyTest {

    private static final String EMPTY_KEY = "emptyKeyListSentinel";
    private static final String TREASURY = "treasury";
    private static final String ATTACKER = "attacker";
    private static final String HOLDER = "holder";

    // The same value as KeyUtils.IMMUTABILITY_SENTINEL_KEY — duplicated here to avoid a
    // test-clients dependency on hapi-utils internals.
    private static final Key IMMUTABILITY_SENTINEL_KEY =
            Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build();

    @HapiTest
    final Stream<DynamicTest> tokenCreateAcceptsEmptyKeyListRoleKeysAsDisabled() {
        return hapiTest(
                // Register the empty-KeyList sentinel under a name so the TokenCreate builder can use it.
                withOpContext((spec, opLog) -> spec.registry().saveKey(EMPTY_KEY, IMMUTABILITY_SENTINEL_KEY)),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(HOLDER).balance(ONE_HUNDRED_HBARS),

                // Every role key accepts the empty-KeyList sentinel at create and stores it verbatim.
                // Sign explicitly (payer + treasury): the sentinel key has no signature control, and role
                // keys are not signing requirements for a creation anyway.
                tokenCreate("allEmptyRolesT")
                        .treasury(TREASURY)
                        .initialSupply(1_000L)
                        .wipeKey(EMPTY_KEY)
                        .supplyKey(EMPTY_KEY)
                        .kycKey(EMPTY_KEY)
                        .freezeKey(EMPTY_KEY)
                        .pauseKey(EMPTY_KEY)
                        .feeScheduleKey(EMPTY_KEY)
                        .metadataKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                getTokenInfo("allEmptyRolesT")
                        .hasEmptyWipeKey()
                        .hasEmptySupplyKey()
                        .hasEmptyKycKey(),

                // An empty role key counts as "no key": the corresponding function is disabled. A token
                // with empty wipe + supply keys rejects mint (supply) and wipe with TOKEN_HAS_NO_*_KEY
                // rather than allowing the operation with no signature.
                tokenCreate("disabledT")
                        .treasury(TREASURY)
                        .initialSupply(1_000L)
                        .wipeKey(EMPTY_KEY)
                        .supplyKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                mintToken("disabledT", 1L).signedBy(DEFAULT_PAYER).hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY),
                tokenAssociate(HOLDER, "disabledT"),
                cryptoTransfer(moving(100L, "disabledT").between(TREASURY, HOLDER)),
                wipeTokenAccount("disabledT", HOLDER, 50L)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(TOKEN_HAS_NO_WIPE_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> emptyKeyListKycAndFreezeKeysDoNotBlockNewAssociations() {
        return hapiTest(
                withOpContext((spec, opLog) -> spec.registry().saveKey(EMPTY_KEY, IMMUTABILITY_SENTINEL_KEY)),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(HOLDER).balance(ONE_HUNDRED_HBARS),

                // Empty KYC/freeze keys mean those functions are disabled, so a new association must
                // default to KYC-granted and unfrozen: no key exists that could ever grant KYC or
                // unfreeze, and otherwise the holder could never receive units.
                tokenCreate("kycFreezeDisabledT")
                        .treasury(TREASURY)
                        .initialSupply(1_000L)
                        .kycKey(EMPTY_KEY)
                        .freezeKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                tokenAssociate(HOLDER, "kycFreezeDisabledT"),

                // The disabled KYC function cannot be invoked...
                grantTokenKyc("kycFreezeDisabledT", HOLDER)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(TOKEN_HAS_NO_KYC_KEY),

                // ...and is not needed: the transfer succeeds because the association defaulted to
                // KYC-granted and unfrozen.
                cryptoTransfer(moving(100L, "kycFreezeDisabledT").between(TREASURY, HOLDER)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCreateRejectsEmptyKeyListWhenAUsableKeyIsRequired() {
        return hapiTest(
                withOpContext((spec, opLog) -> spec.registry().saveKey(EMPTY_KEY, IMMUTABILITY_SENTINEL_KEY)),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),

                // A freeze-default token needs a usable freeze key: with the empty-KeyList sentinel
                // every new relation would be frozen forever with no key able to unfreeze it.
                tokenCreate("frozenForeverT")
                        .treasury(TREASURY)
                        .initialSupply(1_000L)
                        .freezeDefault(true)
                        .freezeKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasPrecheck(TOKEN_HAS_NO_FREEZE_KEY),

                // An NFT needs a usable supply key: with the sentinel nothing could ever be minted.
                tokenCreate("unmintableT")
                        .treasury(TREASURY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .decimals(0)
                        .supplyKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasPrecheck(TOKEN_HAS_NO_SUPPLY_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCreateAllowsEmptyKeyListAdminKeyAsImmutable() {
        return hapiTest(
                withOpContext((spec, opLog) -> spec.registry().saveKey(EMPTY_KEY, IMMUTABILITY_SENTINEL_KEY)),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),

                // An empty-KeyList admin key is the documented way to create an immutable token, so the
                // creation succeeds...
                tokenCreate("immutableT")
                        .treasury(TREASURY)
                        .adminKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),

                // ...and the resulting token is immutable: an admin-gated update is rejected rather than
                // succeeding without any admin signature.
                tokenUpdate("immutableT")
                        .name("renamed")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(TOKEN_IS_IMMUTABLE));
    }

    @HapiTest
    final Stream<DynamicTest> immutableTokenWithSentinelAdminKeyCannotBeDeletedByAnyone() {
        return hapiTest(
                withOpContext((spec, opLog) -> spec.registry().saveKey(EMPTY_KEY, IMMUTABILITY_SENTINEL_KEY)),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ATTACKER).balance(ONE_HUNDRED_HBARS),

                // A token created with the empty-KeyList sentinel as its admin key is immutable.
                tokenCreate("immutableT")
                        .treasury(TREASURY)
                        .adminKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),

                // An attacker (no admin signature — the admin key is unsatisfiable) must NOT be able to
                // delete it. TokenDelete must treat the empty-KeyList admin key as "no admin key" and
                // reject with TOKEN_IS_IMMUTABLE, rather than dropping the unsatisfiable signature
                // requirement and deleting the token.
                tokenDelete("immutableT")
                        .payingWith(ATTACKER)
                        .signedBy(ATTACKER)
                        .hasKnownStatus(TOKEN_IS_IMMUTABLE),

                // The token must still exist after the unauthorized delete attempt.
                getTokenInfo("immutableT"));
    }
}
