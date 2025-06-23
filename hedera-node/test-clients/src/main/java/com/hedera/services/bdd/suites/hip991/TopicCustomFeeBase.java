// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip991;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicCreate;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.ArrayList;
import java.util.List;

public class TopicCustomFeeBase {
    protected static final String TOPIC = "topic";
    protected static final String OWNER = "owner";
    protected static final String FUNGIBLE_TOKEN = "fungibleToken";
    protected static final String ADMIN_KEY = "adminKey";
    protected static final String SUBMIT_KEY = "submitKey";
    protected static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    protected static final String FEE_SCHEDULE_KEY2 = "feeScheduleKey2";
    protected static final String FEE_SCHEDULE_KEY_ECDSA = "feeScheduleKeyECDSA";
    protected static final String FEE_EXEMPT_KEY_PREFIX = "feeExemptKey_";
    protected static final String FREEZE_KEY = "freezeKey";
    protected static final String TOKEN = "TOKEN";
    protected static final String COLLECTOR = "COLLECTOR";

    /*        Submit message entities        */
    protected static final String SUBMITTER = "submitter";
    protected static final String TOKEN_TREASURY = "tokenTreasury";
    protected static final String DENOM_TREASURY = "denomTreasury";
    protected static final String BASE_TOKEN = "baseToken";
    protected static final String SECOND_TOKEN = "secondToken";

    /* tokens with multilayer fees */
    protected static final String TOKEN_PREFIX = "token_";
    protected static final String COLLECTOR_PREFIX = "collector_";
    protected static final String DENOM_TOKEN_PREFIX = "denomToken_";

    // This key is truly invalid, as all Ed25519 public keys must be 32 bytes long
    protected static final Key STRUCTURALLY_INVALID_KEY =
            Key.newBuilder().setEd25519(ByteString.fromHex("ff")).build();

    protected static SpecOperation[] setupBaseKeys() {
        return new SpecOperation[] {
            newKeyNamed(ADMIN_KEY),
            newKeyNamed(SUBMIT_KEY),
            newKeyNamed(FEE_SCHEDULE_KEY),
            newKeyNamed(FEE_SCHEDULE_KEY_ECDSA).shape(KeyShape.SECP256K1)
        };
    }

    protected static SpecOperation[] setupBaseForUpdate() {
        return new SpecOperation[] {
            newKeyNamed(ADMIN_KEY),
            newKeyNamed(SUBMIT_KEY),
            newKeyNamed(FEE_SCHEDULE_KEY),
            newKeyNamed(FEE_SCHEDULE_KEY2),
            newKeyNamed(FREEZE_KEY),
            cryptoCreate(COLLECTOR),
            tokenCreate(TOKEN)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .initialSupply(500)
                    .freezeKey(FREEZE_KEY),
            tokenAssociate(COLLECTOR, TOKEN)
        };
    }

    protected static SpecOperation[] associateFeeTokensAndSubmitter() {
        return new SpecOperation[] {
            cryptoCreate(SUBMITTER).balance(ONE_MILLION_HBARS),
            cryptoCreate(TOKEN_TREASURY),
            tokenCreate(BASE_TOKEN)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .treasury(TOKEN_TREASURY)
                    .initialSupply(1000L),
            tokenCreate(SECOND_TOKEN).treasury(TOKEN_TREASURY).initialSupply(500L),
            tokenAssociate(SUBMITTER, BASE_TOKEN, SECOND_TOKEN),
            cryptoTransfer(
                    moving(500L, BASE_TOKEN).between(TOKEN_TREASURY, SUBMITTER),
                    moving(500L, SECOND_TOKEN).between(TOKEN_TREASURY, SUBMITTER)),
        };
    }

    /**
     * Create and transfer multiple tokens with 2 layer custom fees to given account.
     *
     * @param owner account to transfer tokens
     * @param numberOfTokens the count of tokens to be transferred
     * @return array of spec operations
     */
    protected SpecOperation[] createMultipleTokensWith2LayerFees(String owner, int numberOfTokens) {
        final var specOperations = new ArrayList<SpecOperation>();
        specOperations.add(cryptoCreate(DENOM_TREASURY));
        specOperations.add(cryptoCreate(TOKEN_TREASURY));
        for (int i = 0; i < numberOfTokens; i++) {
            final var tokenName = TOKEN_PREFIX + i;
            specOperations.addAll(createTokenWith2LayerFee(owner, tokenName, false));
        }
        return specOperations.toArray(new SpecOperation[0]);
    }

    // TOPIC_FEE_108
    protected SpecOperation[] associateAllTokensToCollectors(int numberOfTokens) {
        final var collectorName = "collector_";
        final var associateTokensToCollectors = new ArrayList<SpecOperation>();
        for (int i = 0; i < numberOfTokens; i++) {
            associateTokensToCollectors.add(cryptoCreate(collectorName + i).balance(0L));
            associateTokensToCollectors.add(tokenAssociate(collectorName + i, TOKEN_PREFIX + i));
        }
        return associateTokensToCollectors.toArray(SpecOperation[]::new);
    }

    // TOPIC_FEE_108
    protected HapiTopicCreate createTopicWith10Different2layerFees() {
        final var collectorName = "collector_";
        final var topicCreateOp = createTopic(TOPIC);
        for (int i = 0; i < 9; i++) {
            topicCreateOp.withConsensusCustomFee(fixedConsensusHtsFee(1, TOKEN_PREFIX + i, collectorName + i));
        }
        // add one hbar custom fee
        topicCreateOp.withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, collectorName + 0));
        return topicCreateOp;
    }

    // TOPIC_FEE_108
    protected SpecOperation[] assertAllCollectorsBalances(int numberOfCollectors) {
        final var collectorName = "collector_";
        final var assertBalances = new ArrayList<SpecOperation>();
        // assert token balances
        for (int i = 0; i < numberOfCollectors; i++) {
            assertBalances.add(getAccountBalance(collectorName + i).hasTokenBalance(TOKEN_PREFIX + i, 1));
        }
        // add assert for hbar
        assertBalances.add(getAccountBalance(collectorName + 0).hasTinyBars(ONE_HBAR));
        return assertBalances.toArray(SpecOperation[]::new);
    }

    /**
     * Create and transfer tokens with 2 layer custom fees to given account.
     *
     * @param owner account to transfer tokens
     * @param tokenName token name
     * @param createTreasury create treasury or not
     * @return list of spec operations
     */
    protected static List<SpecOperation> createTokenWith2LayerFee(
            String owner, String tokenName, boolean createTreasury) {
        return createTokenWith2LayerFee(owner, tokenName, createTreasury, 1, ONE_HBAR);
    }

    protected static List<SpecOperation> createTokenWith2LayerFee(
            String owner, String tokenName, boolean createTreasury, long htsFee, long hbarFee) {
        final var specOperations = new ArrayList<SpecOperation>();
        final var collectorName = COLLECTOR_PREFIX + tokenName;
        final var denomToken = DENOM_TOKEN_PREFIX + tokenName;
        // if we generate multiple tokens, there will be no need to create treasury every time we create new token
        if (createTreasury) {
            specOperations.add(cryptoCreate(DENOM_TREASURY));
            specOperations.add(cryptoCreate(TOKEN_TREASURY));
        }
        // create first common collector
        specOperations.add(cryptoCreate(collectorName).balance(0L));
        // create denomination token with hbar fee
        specOperations.add(tokenCreate(denomToken)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .treasury(DENOM_TREASURY)
                .withCustom(fixedHbarFee(hbarFee, collectorName)));
        // associate the denomination token with the collector
        specOperations.add(tokenAssociate(collectorName, denomToken));
        // create the token with fixed HTS fee
        specOperations.add(tokenCreate(tokenName)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .treasury(TOKEN_TREASURY)
                .withCustom(fixedHtsFee(htsFee, denomToken, collectorName)));
        // associate the owner with the two new tokens
        specOperations.add(tokenAssociate(owner, tokenName));
        specOperations.add(tokenAssociate(owner, denomToken));
        // transfer the tokens to the owner
        specOperations.add(cryptoTransfer(moving(100L, tokenName).between(TOKEN_TREASURY, owner)));
        specOperations.add(cryptoTransfer(moving(100L, denomToken).between(DENOM_TREASURY, owner)));
        return specOperations;
    }

    protected static List<SpecOperation> createTokenWith4LayerFee(
            String owner, String tokenName, boolean createTreasury) {
        final var specOperations = new ArrayList<SpecOperation>();
        final var collectorName = COLLECTOR_PREFIX + tokenName;
        final var denom1Token = "denom1_" + tokenName;
        final var denom2Token = "denom2_" + tokenName;
        final var denom3Token = "denom3_" + tokenName;
        // if we generate multiple tokens, there will be no need to create treasury every time we create new token
        if (createTreasury) {
            specOperations.add(cryptoCreate(DENOM_TREASURY));
            specOperations.add(cryptoCreate(TOKEN_TREASURY));
        }
        // create first common collector
        specOperations.add(cryptoCreate(collectorName).balance(0L));
        // create first denomination token with hbar fee
        specOperations.add(tokenCreate(denom1Token)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .treasury(DENOM_TREASURY)
                .withCustom(fixedHbarFee(ONE_HBAR, collectorName)));
        // associate the denomination token with the collector
        specOperations.add(tokenAssociate(collectorName, denom1Token));
        // create second denomination token with first denomination token fee
        specOperations.add(tokenCreate(denom2Token)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .treasury(DENOM_TREASURY)
                .withCustom(fixedHtsFee(1, denom1Token, collectorName)));
        // associate the denomination token with the collector
        specOperations.add(tokenAssociate(collectorName, denom2Token));
        // create third denomination token with second denomination token fee
        specOperations.add(tokenCreate(denom3Token)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .treasury(DENOM_TREASURY)
                .withCustom(fixedHtsFee(1, denom2Token, collectorName)));
        // associate the denomination token with the collector
        specOperations.add(tokenAssociate(collectorName, denom3Token));
        // create the token with fixed HTS fee
        specOperations.add(tokenCreate(tokenName)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .treasury(TOKEN_TREASURY)
                .withCustom(fixedHtsFee(1, denom3Token, collectorName)));
        // associate the owner with the two new tokens
        specOperations.add(tokenAssociate(owner, tokenName));
        specOperations.add(tokenAssociate(owner, denom1Token));
        specOperations.add(tokenAssociate(owner, denom2Token));
        specOperations.add(tokenAssociate(owner, denom3Token));
        // transfer the tokens to the owner
        specOperations.add(cryptoTransfer(moving(100L, tokenName).between(TOKEN_TREASURY, owner)));
        specOperations.add(cryptoTransfer(moving(100L, denom1Token).between(DENOM_TREASURY, owner)));
        specOperations.add(cryptoTransfer(moving(100L, denom2Token).between(DENOM_TREASURY, owner)));
        specOperations.add(cryptoTransfer(moving(100L, denom3Token).between(DENOM_TREASURY, owner)));
        return specOperations;
    }

    protected static SpecOperation[] newNamedKeysForFEKL(int count) {
        final var list = new ArrayList<SpecOperation>();
        for (int i = 0; i < count; i++) {
            list.add(newKeyNamed(FEE_EXEMPT_KEY_PREFIX + i));
        }
        return list.toArray(new SpecOperation[0]);
    }

    protected static String[] feeExemptKeyNames(int count) {
        final var list = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            list.add(FEE_EXEMPT_KEY_PREFIX + i);
        }
        return list.toArray(new String[0]);
    }
}
