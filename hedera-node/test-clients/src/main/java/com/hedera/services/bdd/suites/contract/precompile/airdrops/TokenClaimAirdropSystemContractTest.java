/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNftPendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.checkForBalances;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareAccountAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareAirdrops;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareTokenAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareTokensAndBalances;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
@OrderedInIsolation
class TokenClaimAirdropSystemContractTest {

    @Contract(contract = "ClaimAirdrop", creationGas = 1_000_000L)
    static SpecContract claimAirdrop;

    @Account(name = "sender", tinybarBalance = 100_000_000_000L)
    static SpecAccount sender;

    @Account(name = "receiver", maxAutoAssociations = 0)
    static SpecAccount receiver;

    @FungibleToken(name = "token", initialSupply = 1000)
    static SpecFungibleToken token;

    @BeforeAll
    public static void setUp(final @NonNull TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                sender.authorizeContract(claimAirdrop),
                receiver.authorizeContract(claimAirdrop),
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 1000, token));
    }

    @Order(0)
    @HapiTest
    @DisplayName("Can claim  1 fungible airdrop")
    public Stream<DynamicTest> claimAirdrop() {
        return hapiTest(
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0)),
                tokenAirdrop(moving(10, token.name()).between(sender.name(), receiver.name()))
                        .payingWith(sender.name())
                        .via("tokenAirdrop"),
                getTxnRecord("tokenAirdrop")
                        .hasPriority(recordWith()
                                .pendingAirdrops(includingFungiblePendingAirdrop(
                                        moving(10, token.name()).between(sender.name(), receiver.name())))),
                claimAirdrop
                        .call("claim", sender, receiver, token)
                        .payingWith(receiver)
                        .via("claimAirdrop"),
                getTxnRecord("claimAirdrop").hasPriority(recordWith().pendingAirdropsCount(0)),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10)));
    }

    @Order(1)
    @HapiTest
    @DisplayName("Can claim 1 nft airdrop")
    public Stream<DynamicTest> claimNftAirdrop(@NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft) {
        return hapiTest(
                sender.associateTokens(nft),
                nft.treasury().transferNFTsTo(sender, nft, 1L),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0)),
                tokenAirdrop(movingUnique(nft.name(), 1L).between(sender.name(), receiver.name()))
                        .payingWith(sender.name())
                        .via("tokenAirdrop"),
                getTxnRecord("tokenAirdrop")
                        .hasPriority(recordWith()
                                .pendingAirdrops(includingNftPendingAirdrop(
                                        movingUnique(nft.name(), 1L).between(sender.name(), receiver.name())))),
                claimAirdrop
                        .call("claimNFTAirdrop", sender, receiver, nft, 1L)
                        .payingWith(receiver)
                        .via("claimNFTAirdrop"),
                getTxnRecord("claimNFTAirdrop").hasPriority(recordWith().pendingAirdropsCount(0)),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1)));
    }

    @Order(2)
    @HapiTest
    @DisplayName("Can claim 10 fungible airdrops")
    public Stream<DynamicTest> claim10Airdrops(
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token4,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token5,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft4,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft5) {
        final var tokenList = List.of(token1, token2, token3, token4, token5);
        final var nftList = List.of(nft1, nft2, nft3, nft4, nft5);
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, prepareTokensAndBalances(sender, receiver, tokenList, nftList));
            prepareAirdrops(sender, receiver, tokenList, nftList, spec);
            final var senders = prepareAccountAddresses(
                    spec, sender, sender, sender, sender, sender, sender, sender, sender, sender, sender);
            final var receivers = prepareAccountAddresses(
                    spec, receiver, receiver, receiver, receiver, receiver, receiver, receiver, receiver, receiver,
                    receiver);
            final var tokens = prepareTokenAddresses(spec, token1, token2, token3, token4, token5);
            final var nfts = prepareTokenAddresses(spec, nft1, nft2, nft3, nft4, nft5);
            final var combined =
                    Stream.concat(Arrays.stream(tokens), Arrays.stream(nfts)).toArray(Address[]::new);
            final var serials = new long[] {0L, 0L, 0L, 0L, 0L, 1L, 1L, 1L, 1L, 1L};
            allRunFor(
                    spec,
                    claimAirdrop
                            .call("claimAirdrops", senders, receivers, combined, serials)
                            .via("claimAirdrops"),
                    getTxnRecord("claimAirdrops").hasPriority(recordWith().pendingAirdropsCount(0)),
                    checkForBalances(receiver, tokenList, nftList));
        }));
    }

    @Order(3)
    @HapiTest
    @DisplayName("Can claim 3 fungible airdrops")
    public Stream<DynamicTest> claim3Airdrops(
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3) {
        final var tokenList = List.of(token1, token2, token3);
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, prepareTokensAndBalances(sender, receiver, tokenList, List.of()));
            prepareAirdrops(sender, receiver, tokenList, List.of(), spec);
            final var senders = prepareAccountAddresses(spec, sender, sender, sender);
            final var receivers = prepareAccountAddresses(spec, receiver, receiver, receiver);
            final var tokens = prepareTokenAddresses(spec, token1, token2, token3);
            final var serials = new long[] {0L, 0L, 0L};
            allRunFor(
                    spec,
                    claimAirdrop
                            .call("claimAirdrops", senders, receivers, tokens, serials)
                            .via("claimAirdrops"),
                    getTxnRecord("claimAirdrops").hasPriority(recordWith().pendingAirdropsCount(0)),
                    checkForBalances(receiver, tokenList, List.of()));
        }));
    }

    @Order(4)
    @HapiTest
    @DisplayName("Fails to claim 11 pending airdrops")
    public Stream<DynamicTest> failToClaim11Airdrops(
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token4,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token5,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token6,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft4,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft5) {
        final var tokenList = List.of(token1, token2, token3, token4, token5, token6);
        final var nftList = List.of(nft1, nft2, nft3, nft4, nft5);
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, prepareTokensAndBalances(sender, receiver, tokenList, nftList));
            // Spread transactions to avoid hitting the max airdrops limit
            prepareAirdrops(sender, receiver, List.of(token1, token2, token3), List.of(), spec);
            prepareAirdrops(sender, receiver, List.of(token4, token5, token6), List.of(), spec);
            prepareAirdrops(sender, receiver, List.of(), nftList, spec);
            final var senders = prepareAccountAddresses(
                    spec, sender, sender, sender, sender, sender, sender, sender, sender, sender, sender, sender);
            final var receivers = prepareAccountAddresses(
                    spec, receiver, receiver, receiver, receiver, receiver, receiver, receiver, receiver, receiver,
                    receiver, receiver);
            final var tokens = prepareTokenAddresses(spec, token1, token2, token3, token4, token5, token6);
            final var nfts = prepareTokenAddresses(spec, nft1, nft2, nft3, nft4, nft5);
            final var combined =
                    Stream.concat(Arrays.stream(tokens), Arrays.stream(nfts)).toArray(Address[]::new);
            final var serials = new long[] {0L, 0L, 0L, 0L, 0L, 0L, 1L, 1L, 1L, 1L, 1L};
            allRunFor(
                    spec,
                    claimAirdrop
                            .call("claimAirdrops", senders, receivers, combined, serials)
                            .andAssert(txn ->
                                    txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, PENDING_AIRDROP_ID_LIST_TOO_LONG)));
        }));
    }

    @Order(5)
    @HapiTest
    @DisplayName("Fails to claim pending airdrop with invalid token")
    public Stream<DynamicTest> failToClaim1AirdropWithInvalidToken() {
        return hapiTest(claimAirdrop
                .call("claim", sender, receiver, receiver)
                .payingWith(sender)
                .via("claimAirdrop")
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)));
    }

    @Order(6)
    @HapiTest
    @DisplayName("Fails to claim pending airdrop with invalid sender")
    public Stream<DynamicTest> failToClaim1AirdropWithInvalidSender() {
        return hapiTest(claimAirdrop
                .call("claim", token, receiver, token)
                .payingWith(sender)
                .via("claimAirdrop")
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_PENDING_AIRDROP_ID)));
    }

    @Order(7)
    @HapiTest
    @DisplayName("Fails to claim airdrop having no pending airdrops")
    public Stream<DynamicTest> failToClaimAirdropWhenThereAreNoPending() {
        return hapiTest(claimAirdrop
                .call("claim", sender, receiver, token)
                .payingWith(sender)
                .via("claimAirdrop")
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_PENDING_AIRDROP_ID)));
    }

    @Order(8)
    @HapiTest
    @DisplayName("Fails to claim pending airdrop with invalid receiver")
    public Stream<DynamicTest> failToClaim1AirdropWithInvalidReceiver() {
        return hapiTest(claimAirdrop
                .call("claim", sender, token, token)
                .payingWith(sender)
                .via("claimAirdrop")
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_ACCOUNT_ID)));
    }

    @Order(9)
    @HapiTest
    @DisplayName("Fails to claim nft airdrop with invalid nft")
    public Stream<DynamicTest> failToClaim1AirdropWithInvalidNft() {
        return hapiTest(claimAirdrop
                .call("claimNFTAirdrop", sender, receiver, receiver, 1L)
                .payingWith(sender)
                .via("claimAirdrop")
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)));
    }

    @Order(10)
    @HapiTest
    @DisplayName("Fails to claim nft airdrop with invalid nft serial")
    public Stream<DynamicTest> failToClaim1AirdropWithInvalidSerial(@NonFungibleToken final SpecNonFungibleToken nft) {
        return hapiTest(
                sender.associateTokens(nft),
                claimAirdrop
                        .call("claimNFTAirdrop", sender, receiver, nft, 1L)
                        .payingWith(sender)
                        .via("claimAirdrop")
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_PENDING_AIRDROP_ID)));
    }
}
