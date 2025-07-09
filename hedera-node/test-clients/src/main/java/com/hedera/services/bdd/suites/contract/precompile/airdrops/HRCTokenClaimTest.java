// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.contracts.TokenRedirectContract.HRC904;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class HRCTokenClaimTest {

    private static final String BATCH_OPERATOR = "batchOperator";

    @Account(name = "sender", tinybarBalance = 100_000_000_000L)
    static SpecAccount sender;

    @Account(name = "receiver", tinybarBalance = 100_000_000_000L, maxAutoAssociations = 0)
    static SpecAccount receiver;

    @FungibleToken(name = "token", initialSupply = 1_000_000L)
    static SpecFungibleToken token;

    @Account(name = "sender2", tinybarBalance = 100_000_000_000L)
    static SpecAccount sender2;

    @Account(name = "receiver2", tinybarBalance = 100_000_000_000L, maxAutoAssociations = 0)
    static SpecAccount receiver2;

    @FungibleToken(name = "token2", initialSupply = 1_000_000L)
    static SpecFungibleToken token2;

    @NonFungibleToken(name = "nft", numPreMints = 1)
    static SpecNonFungibleToken nft;

    @BeforeAll
    public static void setup(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                sender.associateTokens(token, nft),
                token.treasury().transferUnitsTo(sender, 10L, token),
                nft.treasury().transferNFTsTo(sender, nft, 1L));

        lifecycle.doAdhoc(sender2.associateTokens(token2), token2.treasury().transferUnitsTo(sender2, 10L, token2));

        lifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50",
                "contracts.throttle.throttleByGas",
                "false"));
        lifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    @DisplayName("Can claim airdrop of fungible token")
    public Stream<DynamicTest> canClaimAirdropOfFungibleToken() {
        return hapiTest(
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                tokenAirdrop(moving(10L, token.name()).between(sender.name(), receiver.name()))
                        .payingWith(sender.name()),
                token.call(HRC904, "claimAirdropFT", sender)
                        .payingWith(receiver)
                        .with(call -> call.signingWith(receiver.name())),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)));
    }

    @HapiTest
    @DisplayName("Can claim atomic airdrop of fungible token")
    public Stream<DynamicTest> canClaimAtomicAirdropOfFungibleToken() {
        return hapiTest(
                receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 0L)),
                tokenAirdrop(moving(10L, token2.name()).between(sender2.name(), receiver2.name()))
                        .payingWith(sender2.name()),
                token2.call(HRC904, "claimAirdropFT", sender2)
                        .wrappedInBatchOperation(BATCH_OPERATOR)
                        .payingWith(receiver2)
                        .with(call -> call.signingWith(receiver2.name())),
                receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 10L)));
    }

    @HapiTest
    @DisplayName("Can claim airdrop of nft token")
    public Stream<DynamicTest> canClaimAirdropOfNftToken() {
        return hapiTest(
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                tokenAirdrop(TokenMovement.movingUnique(nft.name(), 1L).between(sender.name(), receiver.name()))
                        .payingWith(sender.name()),
                nft.call(HRC904, "claimAirdropNFT", sender, 1L)
                        .payingWith(receiver)
                        .with(call -> call.signingWith(receiver.name())),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)));
    }

    @HapiTest
    @DisplayName("Cannot claim airdrop if not existing")
    public Stream<DynamicTest> cannotClaimAirdropWhenNotExisting() {
        return hapiTest(token.call(HRC904, "claimAirdropFT", sender)
                .payingWith(receiver)
                .with(call -> call.signingWith(receiver.name()))
                .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName("Cannot claim airdrop if sender not existing")
    public Stream<DynamicTest> cannotClaimAirdropWhenSenderNotExisting() {
        return hapiTest(token.call(HRC904, "claimAirdropFT", token)
                .payingWith(receiver)
                .with(call -> call.signingWith(receiver.name()))
                .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName("Cannot claim nft airdrop if not existing")
    public Stream<DynamicTest> cannotClaimNftAirdropWhenNotExisting() {
        return hapiTest(nft.call(HRC904, "claimAirdropNFT", sender, 1L)
                .payingWith(receiver)
                .with(call -> call.signingWith(receiver.name()))
                .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName("Cannot claim nft airdrop if sender not existing")
    public Stream<DynamicTest> cannotClaimNftAirdropWhenSenderNotExisting() {
        return hapiTest(nft.call(HRC904, "claimAirdropNFT", nft, 1L)
                .payingWith(receiver)
                .with(call -> call.signingWith(receiver.name()))
                .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INVALID_PENDING_AIRDROP_ID)));
    }
}
