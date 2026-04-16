// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.contracts.TokenRedirectContract.HRC904;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_OWNER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableReason;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class HRCTokenRejectTest {

    @Account(tinybarBalance = 100_000_000_000L)
    static SpecAccount sender;

    @BeforeAll
    public static void setUp(@NonNull TestLifecycle lifecycle) {
        lifecycle.doAdhoc(sender.getInfo());
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("HRC rejectTokenFT works")
    public Stream<DynamicTest> hrcFungibleWorks(@FungibleToken(initialSupply = 1000) SpecFungibleToken token) {
        return hapiTest(
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 10L, token),
                token.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 990L)),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)),
                token.call(HRC904, "rejectTokenFT").with(call -> call.payingWith(sender.name())),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                token.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1000L)));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("HRC rejectTokenNFTs works")
    public Stream<DynamicTest> hrcNftWorks(@NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft) {
        return hapiTest(
                sender.associateTokens(nft),
                nft.treasury().transferNFTsTo(sender, nft, 1L),
                nft.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)),
                nft.call(HRC904, "rejectTokenNFTs", new long[] {1L}).with(call -> call.payingWith(sender.name())),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                nft.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("HRC rejectTokenNFTs works for max allowed serials")
    public Stream<DynamicTest> hrcNftWorksForMultipleSerials(
            @NonFungibleToken(numPreMints = 10) SpecNonFungibleToken nft) {
        return hapiTest(
                sender.associateTokens(nft),
                nft.treasury().transferNFTsTo(sender, nft, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L),
                nft.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 10L)),
                nft.call(HRC904, "rejectTokenNFTs", new long[] {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L})
                        .with(call -> call.payingWith(sender.name()))
                        .gas(1_000_000L),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                nft.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 10L)));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("HRC rejectTokenNFTs fails if account has no nft balance")
    public Stream<DynamicTest> hrcNftFailsIfAccountHasNoBalance(
            @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft) {
        return hapiTest(
                sender.associateTokens(nft),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                nft.call(HRC904, "rejectTokenNFTs", new long[] {1L})
                        .with(call -> call.payingWith(sender.name()))
                        .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INVALID_OWNER_ID)));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("HRC rejectTokenFT fails if account has no token balance")
    public Stream<DynamicTest> hrcFungibleFailsIfAccountHasNoBalance(@FungibleToken SpecFungibleToken token) {
        return hapiTest(
                sender.associateTokens(token),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                token.call(HRC904, "rejectTokenFT")
                        .with(call -> call.payingWith(sender.name()))
                        .andAssert(txn -> txn.hasKnownStatuses(SUCCESS, INSUFFICIENT_TOKEN_BALANCE)));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("HRC rejectTokenNFTs fails if serials exceed limit")
    public Stream<DynamicTest> hrcNftFailsForMultipleSerials(
            @NonFungibleToken(numPreMints = 11) SpecNonFungibleToken nft) {
        return hapiTest(
                sender.associateTokens(nft),
                nft.treasury().transferNFTsTo(sender, nft, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L),
                nft.treasury().transferNFTsTo(sender, nft, 11L),
                sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 11L)),
                nft.call(HRC904, "rejectTokenNFTs", new long[] {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L})
                        .with(call -> call.payingWith(sender.name()))
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatuses(
                                CONTRACT_REVERT_EXECUTED, TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED)));
    }
}
