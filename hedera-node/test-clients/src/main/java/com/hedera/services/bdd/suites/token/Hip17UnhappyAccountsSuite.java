// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
@Tag(MATS)
public class Hip17UnhappyAccountsSuite {
    private static final String MEMO_1 = "memo1";
    private static final String MEMO_2 = "memo2";

    private static final String SUPPLY_KEY = "supplyKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String KYC_KEY = "kycKey";
    private static final String WIPE_KEY = "wipeKey";
    private static final String CLIENT_1 = "Client1";
    private static final String CLIENT_2 = "Client2";
    private static final String TREASURY = "treasury";
    private static final String UNIQUE_TOKEN_A = "TokenA";

    @HapiTest
    final Stream<DynamicTest> uniqueTokenOperationsFailForDeletedAccount() {
        return defaultHapiSpec("UniqueTokenOperationsFailForDeletedAccount")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(WIPE_KEY),
                        cryptoCreate(CLIENT_1),
                        cryptoCreate(TREASURY))
                .when(
                        tokenCreate(UNIQUE_TOKEN_A)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .freezeKey(FREEZE_KEY)
                                .kycKey(KYC_KEY)
                                .wipeKey(WIPE_KEY)
                                .treasury(TREASURY),
                        mintToken(
                                UNIQUE_TOKEN_A,
                                List.of(ByteString.copyFromUtf8(MEMO_1), ByteString.copyFromUtf8(MEMO_2))),
                        tokenAssociate(CLIENT_1, UNIQUE_TOKEN_A),
                        cryptoDelete(CLIENT_1))
                .then(
                        cryptoTransfer(movingUnique(UNIQUE_TOKEN_A, 2L).between(TREASURY, CLIENT_1))
                                .hasKnownStatus(ACCOUNT_DELETED),
                        revokeTokenKyc(UNIQUE_TOKEN_A, CLIENT_1).hasKnownStatus(ACCOUNT_DELETED),
                        grantTokenKyc(UNIQUE_TOKEN_A, CLIENT_1).hasKnownStatus(ACCOUNT_DELETED),
                        tokenFreeze(UNIQUE_TOKEN_A, CLIENT_1).hasKnownStatus(ACCOUNT_DELETED),
                        tokenUnfreeze(UNIQUE_TOKEN_A, CLIENT_1).hasKnownStatus(ACCOUNT_DELETED),
                        wipeTokenAccount(UNIQUE_TOKEN_A, CLIENT_1, List.of(1L)).hasKnownStatus(ACCOUNT_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenOperationsFailForKycRevokedAccount() {
        return defaultHapiSpec("UniqueTokenOperationsFailForKycRevokedAccount")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(WIPE_KEY),
                        cryptoCreate(CLIENT_1),
                        cryptoCreate(CLIENT_2),
                        cryptoCreate(TREASURY))
                .when(
                        tokenCreate(UNIQUE_TOKEN_A)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .freezeKey(FREEZE_KEY)
                                .kycKey(KYC_KEY)
                                .wipeKey(WIPE_KEY)
                                .treasury(TREASURY),
                        mintToken(
                                UNIQUE_TOKEN_A,
                                List.of(ByteString.copyFromUtf8(MEMO_1), ByteString.copyFromUtf8(MEMO_2))),
                        tokenAssociate(CLIENT_1, UNIQUE_TOKEN_A),
                        grantTokenKyc(UNIQUE_TOKEN_A, CLIENT_1),
                        cryptoTransfer(movingUnique(UNIQUE_TOKEN_A, 1L).between(TREASURY, CLIENT_1)),
                        tokenAssociate(CLIENT_2, UNIQUE_TOKEN_A),
                        revokeTokenKyc(UNIQUE_TOKEN_A, CLIENT_1))
                .then(
                        cryptoTransfer(movingUnique(UNIQUE_TOKEN_A, 2L).between(TREASURY, CLIENT_1))
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN),
                        cryptoTransfer(movingUnique(UNIQUE_TOKEN_A, 1L).between(CLIENT_1, CLIENT_2))
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN),
                        wipeTokenAccount(UNIQUE_TOKEN_A, CLIENT_1, List.of(1L))
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenOperationsFailForFrozenAccount() {
        return defaultHapiSpec("UniqueTokenOperationsFailForFrozenAccount")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(WIPE_KEY),
                        cryptoCreate(CLIENT_1),
                        cryptoCreate(CLIENT_2),
                        cryptoCreate(TREASURY))
                .when(
                        tokenCreate(UNIQUE_TOKEN_A)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .freezeKey(FREEZE_KEY)
                                .kycKey(KYC_KEY)
                                .wipeKey(WIPE_KEY)
                                .treasury(TREASURY),
                        mintToken(
                                UNIQUE_TOKEN_A,
                                List.of(ByteString.copyFromUtf8(MEMO_1), ByteString.copyFromUtf8(MEMO_2))),
                        tokenAssociate(CLIENT_1, UNIQUE_TOKEN_A),
                        grantTokenKyc(UNIQUE_TOKEN_A, CLIENT_1),
                        cryptoTransfer(movingUnique(UNIQUE_TOKEN_A, 1L).between(TREASURY, CLIENT_1)),
                        tokenAssociate(CLIENT_2, UNIQUE_TOKEN_A),
                        tokenFreeze(UNIQUE_TOKEN_A, CLIENT_1))
                .then(
                        cryptoTransfer(movingUnique(UNIQUE_TOKEN_A, 2L).between(TREASURY, CLIENT_1))
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                        cryptoTransfer(movingUnique(UNIQUE_TOKEN_A, 1L).between(CLIENT_1, CLIENT_2))
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                        wipeTokenAccount(UNIQUE_TOKEN_A, CLIENT_1, List.of(1L))
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenOperationsFailForDissociatedAccount() {
        return defaultHapiSpec("UniqueTokenOperationsFailForDissociatedAccount")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(WIPE_KEY),
                        cryptoCreate(CLIENT_1),
                        cryptoCreate(TREASURY))
                .when(
                        tokenCreate(UNIQUE_TOKEN_A)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .freezeKey(FREEZE_KEY)
                                .kycKey(KYC_KEY)
                                .wipeKey(WIPE_KEY)
                                .treasury(TREASURY),
                        mintToken(UNIQUE_TOKEN_A, List.of(ByteString.copyFromUtf8("memo"))))
                .then(
                        tokenFreeze(UNIQUE_TOKEN_A, CLIENT_1).hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        tokenUnfreeze(UNIQUE_TOKEN_A, CLIENT_1).hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        grantTokenKyc(UNIQUE_TOKEN_A, CLIENT_1).hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        revokeTokenKyc(UNIQUE_TOKEN_A, CLIENT_1).hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        wipeTokenAccount(UNIQUE_TOKEN_A, CLIENT_1, List.of(1L))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        cryptoTransfer(movingUnique(UNIQUE_TOKEN_A, 1L).between(TREASURY, CLIENT_1))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
    }
}
