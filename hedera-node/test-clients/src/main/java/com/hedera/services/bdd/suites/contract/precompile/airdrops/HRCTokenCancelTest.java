// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.contracts.TokenRedirectContract.HRC904;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class HRCTokenCancelTest {

    @Account(name = "sender", tinybarBalance = 100_000_000_000L)
    static SpecAccount sender;

    @Account(name = "receiver", maxAutoAssociations = 0)
    static SpecAccount receiver;

    @FungibleToken(name = "token", initialSupply = 1_000_000L)
    static SpecFungibleToken token;

    @NonFungibleToken(name = "nft", numPreMints = 1)
    static SpecNonFungibleToken nft;

    @BeforeAll
    public static void setup(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                sender.associateTokens(token, nft),
                token.treasury().transferUnitsTo(sender, 10L, token),
                nft.treasury().transferNFTsTo(sender, nft, 1L));
    }

    @HapiTest
    @DisplayName("Can cancel airdrop of fungible token")
    public Stream<DynamicTest> canCancelAirdropOfFungibleToken() {
        return hapiTest(
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                tokenAirdrop(moving(10L, token.name()).between(sender.name(), receiver.name()))
                        .payingWith(sender.name()),
                token.call(HRC904, "cancelAirdropFT", receiver).with(call -> call.payingWith(sender.name())));
    }

    @HapiTest
    @DisplayName("Can cancel airdrop of nft token")
    public Stream<DynamicTest> canCancelAirdropOfNftToken() {
        return hapiTest(
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                tokenAirdrop(TokenMovement.movingUnique(nft.name(), 1L).between(sender.name(), receiver.name()))
                        .payingWith(sender.name()),
                nft.call(HRC904, "cancelAirdropNFT", receiver, 1L).with(call -> call.payingWith(sender.name())));
    }

    @HapiTest
    @DisplayName("Cannot cancel airdrop if not existing")
    public Stream<DynamicTest> cannotCancelAirdropWhenNotExisting() {
        return hapiTest(token.call(HRC904, "cancelAirdropFT", receiver)
                .with(call -> call.payingWith(sender.name()))
                .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName("Cannot cancel airdrop if receiver not existing")
    public Stream<DynamicTest> cannotCancelAirdropWhenReceiverNotExisting() {
        return hapiTest(token.call(HRC904, "cancelAirdropFT", token)
                .with(call -> call.payingWith(sender.name()))
                .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName("Cannot cancel nft airdrop if not existing")
    public Stream<DynamicTest> cannotCancelNftAirdropWhenNotExisting() {
        return hapiTest(nft.call(HRC904, "cancelAirdropNFT", receiver, 1L)
                .with(call -> call.payingWith(sender.name()))
                .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName("Cannot cancel nft airdrop if receiver not existing")
    public Stream<DynamicTest> cannotCancelNftAirdropWhenReceiverNotExisting() {
        return hapiTest(nft.call(HRC904, "cancelAirdropNFT", nft, 1L)
                .with(call -> call.payingWith(sender.name()))
                .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INVALID_PENDING_AIRDROP_ID)));
    }
}
