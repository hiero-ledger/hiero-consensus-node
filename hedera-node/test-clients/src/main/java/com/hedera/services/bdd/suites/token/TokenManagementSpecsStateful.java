// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.restoreDefault;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_NFTS_IN_PRICE_REGIME_HAVE_BEEN_MINTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
public class TokenManagementSpecsStateful {
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    public static final String INVALID_ACCOUNT = "999.999.999";

    @HapiTest
    final Stream<DynamicTest> freezeMgmtFailureCasesWork() {
        var unfreezableToken = "without";
        var freezableToken = "withPlusDefaultTrue";

        return defaultHapiSpec("FreezeMgmtFailureCasesWork")
                .given(
                        fileUpdate(APP_PROPERTIES)
                                .payingWith(ADDRESS_BOOK_CONTROL)
                                .overridingProps(Map.of("tokens.maxPerAccount", "" + 1000)),
                        newKeyNamed("oneFreeze"),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate("go").balance(0L),
                        tokenCreate(unfreezableToken).treasury(TOKEN_TREASURY),
                        tokenCreate(freezableToken)
                                .freezeDefault(true)
                                .freezeKey("oneFreeze")
                                .treasury(TOKEN_TREASURY))
                .when(
                        tokenFreeze(unfreezableToken, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY),
                        tokenFreeze(freezableToken, INVALID_ACCOUNT).hasKnownStatus(INVALID_ACCOUNT_ID),
                        tokenFreeze(freezableToken, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        tokenFreeze(freezableToken, "go").hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        tokenUnfreeze(freezableToken, "go").hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        tokenUnfreeze(unfreezableToken, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY),
                        tokenUnfreeze(freezableToken, INVALID_ACCOUNT).hasKnownStatus(INVALID_ACCOUNT_ID),
                        tokenUnfreeze(freezableToken, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .hasKnownStatus(INVALID_SIGNATURE))
                .then(getTokenInfo(unfreezableToken)
                        .hasRegisteredId(unfreezableToken)
                        .logged());
    }

    @LeakyHapiTest(overrides = {"tokens.nfts.maxAllowedMints"})
    final Stream<DynamicTest> nftMintingCapIsEnforced() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                tokenCreate(FUNGIBLE_TOKEN)
                        .initialSupply(0)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey("supplyKey"),
                mintToken(FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("Why not?"))),
                overriding("tokens.nfts.maxAllowedMints", "1"),
                mintToken(FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("Again, why not?")))
                        .hasKnownStatus(MAX_NFTS_IN_PRICE_REGIME_HAVE_BEEN_MINTED),
                restoreDefault("tokens.nfts.maxAllowedMints"),
                mintToken(FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("Again, why not?"))));
    }

    @HapiTest
    final Stream<DynamicTest> requireCorrectFreezeKeys() {
        final var FREEZE_KEY = "FreezeKey";
        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(TOKEN),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(TOKEN).freezeKey(FREEZE_KEY).treasury(TOKEN_TREASURY),
                // freeze token without the freeze key
                tokenFreeze(TOKEN, TOKEN_TREASURY).signedBy(GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                // freeze token with the freeze key
                tokenFreeze(TOKEN, TOKEN_TREASURY).signedBy(GENESIS, FREEZE_KEY).hasKnownStatus(SUCCESS),
                // unfreeze the token without the freeze key
                tokenUnfreeze(TOKEN, TOKEN_TREASURY).signedBy(GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                // unfreeze the token without the freeze key
                tokenUnfreeze(TOKEN, TOKEN_TREASURY)
                        .signedBy(GENESIS, FREEZE_KEY)
                        .hasKnownStatus(SUCCESS));
    }
}
