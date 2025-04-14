// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.leaky;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount.INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiPrng;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.A_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_CREATE_SPONSOR;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_CREATE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_INFINITE_SUPPLY_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.TOKEN_A_CREATE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.VALID_ALIAS;
import static com.hedera.services.bdd.suites.file.FileUpdateSuite.CIVILIAN;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.MULTI_KEY;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.TRANSFER_TXN;
import static com.hedera.services.bdd.suites.util.UtilPrngSuite.BOB;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class FeatureFlagSuite {
    @LeakyHapiTest(
            overrides = {
                "autoCreation.enabled",
                "utilPrng.isEnabled",
                "tokens.autoCreations.isEnabled",
                "lazyCreation.enabled"
            })
    final Stream<DynamicTest> disableAllFeatureFlagsAndConfirmNotSupported() {
        return hapiTest(
                overridingAllOf(Map.of(
                        "autoCreation.enabled", "false",
                        "utilPrng.isEnabled", "false",
                        "tokens.autoCreations.isEnabled", "false",
                        "lazyCreation.enabled", "false")),
                inParallel(
                                confirmAutoCreationNotSupported(),
                                confirmUtilPrngNotSupported(),
                                confirmKeyAliasAutoCreationNotSupported(),
                                confirmHollowAccountCreationNotSupported())
                        .failOnErrors());
    }

    private HapiSpecOperation confirmAutoCreationNotSupported() {
        final var aliasKey = "autoCreationKey";
        return UtilVerbs.blockingOrder(
                newKeyNamed(aliasKey),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, aliasKey, ONE_HBAR))
                        .hasKnownStatus(NOT_SUPPORTED));
    }

    private HapiSpecOperation confirmUtilPrngNotSupported() {
        return UtilVerbs.blockingOrder(
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                hapiPrng().payingWith(BOB).via("baseTxn").blankMemo().logged(),
                getTxnRecord("baseTxn").hasNoPseudoRandomData(),
                hapiPrng(10).payingWith(BOB).via("plusRangeTxn").blankMemo().logged(),
                getTxnRecord("plusRangeTxn").hasNoPseudoRandomData());
    }

    private HapiSpecOperation confirmHollowAccountCreationNotSupported() {
        return UtilVerbs.blockingOrder(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op = cryptoTransfer(tinyBarsFromTo(LAZY_CREATE_SPONSOR, evmAddress, ONE_HUNDRED_HBARS))
                            .hasKnownStatus(NOT_SUPPORTED)
                            .via(TRANSFER_TXN);
                    allRunFor(spec, op);
                }));
    }

    private HapiSpecOperation confirmKeyAliasAutoCreationNotSupported() {
        final var initialTokenSupply = 1000;
        final var fungibleTokenXfer = "fungibleTokenXfer";
        final var nftXfer = "nftXfer";

        return UtilVerbs.blockingOrder(
                newKeyNamed(VALID_ALIAS),
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(A_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(initialTokenSupply)
                        .treasury(TOKEN_TREASURY)
                        .via(TOKEN_A_CREATE),
                tokenCreate(NFT_INFINITE_SUPPLY_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .via(NFT_CREATE),
                mintToken(
                        NFT_INFINITE_SUPPLY_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR).maxAutomaticTokenAssociations(2),
                tokenAssociate(CIVILIAN, NFT_INFINITE_SUPPLY_TOKEN),
                cryptoTransfer(
                        moving(100, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN),
                        movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1L, 2L).between(TOKEN_TREASURY, CIVILIAN)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(A_TOKEN).balance(100)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(NFT_INFINITE_SUPPLY_TOKEN)),
                cryptoTransfer(moving(10, A_TOKEN).between(CIVILIAN, VALID_ALIAS))
                        .via(fungibleTokenXfer)
                        .payingWith(CIVILIAN)
                        .hasKnownStatus(NOT_SUPPORTED)
                        .logged(),
                cryptoTransfer(movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1, 2).between(CIVILIAN, VALID_ALIAS))
                        .via(nftXfer)
                        .payingWith(CIVILIAN)
                        .hasKnownStatus(NOT_SUPPORTED)
                        .logged(),
                getTxnRecord(fungibleTokenXfer).andAllChildRecords().hasNonStakingChildRecordCount(0),
                getTxnRecord(nftXfer).andAllChildRecords().hasNonStakingChildRecordCount(0),
                cryptoTransfer(tinyBarsFromToWithAlias(CIVILIAN, VALID_ALIAS, ONE_HBAR))
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, VALID_ALIAS)
                        .via(TRANSFER_TXN)
                        .hasKnownStatus(NOT_SUPPORTED),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().hasNonStakingChildRecordCount(0));
    }
}
