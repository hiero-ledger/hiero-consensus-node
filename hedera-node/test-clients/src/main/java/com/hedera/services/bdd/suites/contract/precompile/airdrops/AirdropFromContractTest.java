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

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.accountId;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.checkForBalances;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.checkForEmptyBalance;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareAccountAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareContractAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareTokenAddresses;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.keys.KeyShape;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@OrderedInIsolation
@Tag(SMART_CONTRACT)
public class AirdropFromContractTest {

    @Contract(contract = "Airdrop")
    static SpecContract airdropContract;

    @Order(0)
    @HapiTest
    @DisplayName("Contract Airdrops a token to a receiver who is associated to the token")
    public Stream<DynamicTest> airdropTokenToAccount(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(
                sender.authorizeContract(airdropContract),
                sender.associateTokens(token),
                airdropContract.associateTokens(token),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                token.treasury().transferUnitsTo(sender, 1_000L, token),
                airdropContract
                        .call("tokenAirdrop", token, sender, receiver, 10L)
                        .sending(85_000_000L)
                        .gas(1_500_000L)
                        .via("AirdropTxn"),
                getTxnRecord("AirdropTxn").hasPriority(recordWith().pendingAirdropsCount(0)),
                receiver.getBalance()
                        .andAssert(balance -> balance.hasTokenBalance(token.name(), 10L))
                        .andAssert(balance -> balance.hasTinyBars(100L)),
                receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(1)));
    }

    @Order(1)
    @HapiTest
    @DisplayName("Airdrop token from contact with custom fee")
    public Stream<DynamicTest> airdropTokenWithCustomFee(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull
                    @FungibleToken(
                            initialSupply = 1_000_000L,
                            keys = {FEE_SCHEDULE_KEY})
                    final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token),
                    airdropContract.associateTokens(token),
                    sender.authorizeContract(airdropContract),
                    token.treasury().transferUnitsTo(sender, 500_000L, token),
                    receiver.getBalance().andAssert(balance -> balance.hasTinyBars(100L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    tokenFeeScheduleUpdate(token.name())
                            .withCustom(fractionalFeeNetOfTransfers(
                                    1L, 10L, 1L, OptionalLong.of(100L), airdropContract.name())));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn"),
                    getTxnRecord("AirdropTxn").hasPriority(recordWith().pendingAirdropsCount(0)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 500989)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10)),
                    airdropContract.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1L)),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(1)));
        }));
    }

    @Order(2)
    @HapiTest
    @DisplayName("Airdrop multiple tokens from contract that is already associated with them")
    public Stream<DynamicTest> airdropMultipleTokens(
            @NonNull @Account(maxAutoAssociations = 5, tinybarBalance = 100L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token1, token2, token3, nft1, nft2, nft3),
                    sender.authorizeContract(airdropContract),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2),
                    token3.treasury().transferUnitsTo(sender, 1_000L, token3));
            allRunFor(
                    spec,
                    nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                    nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                    nft3.treasury().transferNFTsTo(sender, nft3, 1L),
                    receiver.associateTokens(token1, token2, token3, nft1, nft2, nft3));
            allRunFor(spec, checkForEmptyBalance(receiver, List.of(token1, token2, token3), List.of()));
            final var serials = new long[] {1L, 1L, 1L};
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "mixedAirdrop",
                                    prepareTokenAddresses(spec, token1, token2, token3),
                                    prepareTokenAddresses(spec, nft1, nft2, nft3),
                                    prepareContractAddresses(spec, sender, sender, sender),
                                    prepareAccountAddresses(spec, receiver, receiver, receiver),
                                    prepareContractAddresses(spec, sender, sender, sender),
                                    prepareAccountAddresses(spec, receiver, receiver, receiver),
                                    10L,
                                    serials)
                            .gas(1750000),
                    checkForBalances(receiver, List.of(token1, token2, token3), List.of(nft1, nft2, nft3)));
        }));
    }

    @Order(3)
    @HapiTest
    @DisplayName("Airdrop token from contact")
    public Stream<DynamicTest> airdropTokenToAccountWithFreeSlots(
            @NonNull @Account(maxAutoAssociations = 0, tinybarBalance = 100L) final SpecAccount receiver,
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(
                receiver.associateTokens(token),
                sender.associateTokens(token),
                sender.authorizeContract(airdropContract),
                token.treasury().transferUnitsTo(sender, 500_000L, token),
                airdropContract
                        .call("tokenAirdrop", token, sender, receiver, 10L)
                        .gas(10000000)
                        .sending(5L)
                        .via("AirdropTxn"),
                getTxnRecord("AirdropTxn").logged().andAllChildRecords(),
                receiver.getBalance()
                        .andAssert(balance -> balance.hasTokenBalance(token.name(), 10L))
                        .andAssert(balance -> balance.hasTinyBars(100L)),
                receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(0)));
    }

    @Order(5)
    @HapiTest
    @DisplayName("Contract account airdrops a single token to an ECDSA account")
    public Stream<DynamicTest> airdropTokenToECDSAAccount(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {

        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    receiver.getBalance(),
                    newKeyNamed("key").shape(KeyShape.SECP256K1),
                    cryptoUpdate(receiver.name()).key("key"),
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    airdropContract.associateTokens(token),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn"),
                    getTxnRecord("AirdropTxn").hasPriority(recordWith().pendingAirdropsCount(0)),
                    receiver.getBalance()
                            .andAssert(balance -> balance.hasTokenBalance(token.name(), 10L))
                            .andAssert(balance -> balance.hasTinyBars(100L)),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(1)));
        }));
    }

    @Order(4)
    @HapiTest
    @DisplayName("Contract account airdrops a single token to an ED25519 account")
    public Stream<DynamicTest> airdropTokenToED25519Account(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {

        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    receiver.getBalance(),
                    newKeyNamed("key").shape(KeyShape.ED25519),
                    cryptoUpdate(receiver.name()).key("key"),
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    airdropContract.associateTokens(token),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn"),
                    getTxnRecord("AirdropTxn").hasPriority(recordWith().pendingAirdropsCount(0)),
                    receiver.getBalance()
                            .andAssert(balance -> balance.hasTokenBalance(token.name(), 10L))
                            .andAssert(balance -> balance.hasTinyBars(100L)),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(1)));
        }));
    }

    @Order(6)
    @HapiTest
    @DisplayName("Contract account airdrops a single token to an account alias with free association slots")
    public Stream<DynamicTest> airdropToAccountWithFreeAutoAssocSlots(
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @Account(maxAutoAssociations = -1, tinybarBalance = 100L) final SpecAccount receiver,
            @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token,
            @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token, nft),
                    airdropContract.associateTokens(token, nft),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    token.treasury().transferUnitsTo(sender, 1_000L, token),
                    nft.treasury().transferNFTsTo(sender, nft, 1L));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L),
                    airdropContract
                            .call("nftAirdrop", nft, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L),
                    receiver.getInfo().andAssert(info -> info.hasAlreadyUsedAutomaticAssociations(2)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)));
        }));
    }

    @Order(7)
    @HapiTest
    @DisplayName("Contract account airdrops multiple tokens to contract alias with unlimited association slots")
    public Stream<DynamicTest> airdropToAccountWithUnlimitedAutoAssocSlots(
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L, maxAutoAssociations = -1)
                    final SpecContract receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token1, token2),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2));
            allRunFor(spec, checkForEmptyBalance(receiver, List.of(token1, token2), List.of()));
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "tokenNAmountAirdrops",
                                    prepareTokenAddresses(spec, List.of(token1, token2)),
                                    prepareContractAddresses(spec, List.of(sender, sender)),
                                    prepareContractAddresses(spec, List.of(receiver, receiver)),
                                    10L)
                            .sending(450_000_000L)
                            .gas(1_750_000L)
                            .via("AirdropTxn"),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 10L)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 10L)),
                    getTxnRecord("AirdropTxn").hasChildRecords(recordWith().pendingAirdropsCount(0)));
        }));
    }

    @Order(8)
    @HapiTest
    @DisplayName("Contract account airdrops a multiple tokens to an account alias without free association slots")
    public Stream<DynamicTest> airdropToAccountWithNoFreeAutoAssocSlots(
            @NonNull @Account(maxAutoAssociations = 0, tinybarBalance = 100_000_000L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token2) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token1, token2),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2));
            allRunFor(spec, checkForEmptyBalance(receiver, List.of(token1, token2), List.of()));
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "tokenNAmountAirdrops",
                                    prepareTokenAddresses(spec, List.of(token1, token2)),
                                    prepareContractAddresses(spec, List.of(sender, sender)),
                                    prepareAccountAddresses(spec, List.of(receiver, receiver)),
                                    10L)
                            .sending(450_000_000L)
                            .gas(1_750_000L)
                            .via("AirdropTxn"),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 0L)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 0L)),
                    getTxnRecord("AirdropTxn")
                            .hasChildRecords(recordWith().pendingAirdropsCount(2))
                            .hasChildRecords(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(10L, token1.name()).between(sender.name(), receiver.name()),
                                            moving(10L, token2.name()).between(sender.name(), receiver.name())))));
        }));
    }

    @Order(9)
    @HapiTest
    @DisplayName("Contract account airdrops a multiple tokens to an address with no account on it.")
    public Stream<DynamicTest> airdropToAddressWithNoAccount(
            @NonNull @Account(maxAutoAssociations = 10, tinybarBalance = 100L) final SpecAccount receiver,
            @Contract(contract = "EmptyOne", creationGas = 10_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {

        final var hollowAccountKey = "hollowAccountKey";
        final AtomicReference<ByteString> hollowAccountAlias = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(hollowAccountKey).shape(KeyShape.SECP256K1),
                sender.getBalance(),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(hollowAccountKey)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddressBytes = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    hollowAccountAlias.set(evmAddressBytes);
                    contractCall(
                                    "Airdrop",
                                    "tokenAirdrop",
                                    token,
                                    sender.addressOn(spec.targetNetworkOrThrow()),
                                    asAddress(accountId(hollowAccountAlias.get())),
                                    10L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn");
                    getAutoCreatedAccountBalance(hollowAccountKey).hasTokenBalance(token.name(), 10L);
                }));
    }

    @Order(10)
    @HapiTest
    @DisplayName(
            "Contract airdrops a token to an account, then the receiver claims the airdrop, then the sender airdrops the same token again ")
    public Stream<DynamicTest> airdropToAccountAgainAfterReceiverClaims(
            @NonNull @Account(maxAutoAssociations = 0, tinybarBalance = 100_000_000L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    token.treasury().transferUnitsTo(sender, 10L, token));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 5L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn"),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    getTxnRecord("AirdropTxn").hasChildRecords(recordWith().pendingAirdropsCount(1)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)));
            allRunFor(
                    spec,
                    tokenClaimAirdrop(pendingAirdrop(sender.name(), receiver.name(), token.name()))
                            .payingWith(receiver.name()),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 5L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 5L)));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 3L)
                            .sending(85_000_000L)
                            .gas(1_500_000L),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 8L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 2L)));
        }));
    }

    @Order(11)
    @HapiTest
    @DisplayName("Contract airdrops multiple times that FT to an account, then only one pending transaction is created")
    public Stream<DynamicTest> airdropFTToAccountMultipleTimes(
            @NonNull @Account(maxAutoAssociations = 0, tinybarBalance = 100_000_000L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    token.treasury().transferUnitsTo(sender, 10L, token));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn1"),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn2"),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn3"),
                    getTxnRecord("AirdropTxn1").hasChildRecords(recordWith().pendingAirdropsCount(1)),
                    getTxnRecord("AirdropTxn2").hasChildRecords(recordWith().pendingAirdropsCount(1)),
                    getTxnRecord("AirdropTxn3").hasChildRecords(recordWith().pendingAirdropsCount(1)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)));
            allRunFor(
                    spec,
                    tokenClaimAirdrop(pendingAirdrop(sender.name(), receiver.name(), token.name()))
                            .payingWith(receiver.name()),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 3L)));
        }));
    }

    @Order(12)
    @HapiTest
    @DisplayName(
            "Contract airdrops a FT to an account, then associate the receiver, then airdrops the same token again")
    public Stream<DynamicTest> airdropFTToAccountThenAssociateTheReceiverAndAirdropAgain(
            @NonNull @Account(maxAutoAssociations = 0, tinybarBalance = 100_000_000L) final SpecAccount receiver,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.authorizeContract(airdropContract),
                    sender.associateTokens(token),
                    token.treasury().transferUnitsTo(sender, 10L, token));
            allRunFor(
                    spec,
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn1"),
                    getTxnRecord("AirdropTxn1").hasChildRecords(recordWith().pendingAirdropsCount(1)),
                    receiver.associateTokens(token),
                    airdropContract
                            .call("tokenAirdrop", token, sender, receiver, 1L)
                            .sending(85_000_000L)
                            .gas(1_500_000L)
                            .via("AirdropTxn2"),
                    getTxnRecord("AirdropTxn2").hasChildRecords(recordWith().pendingAirdropsCount(0)),
                    receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1L)));
        }));
    }

    @Order(13)
    @HapiTest
    @DisplayName("Multiple contracts airdrop tokens to multiple accounts.")
    public Stream<DynamicTest> multipleContractsAirdropTokensToMultipleAccounts(
            @NonNull @Account(maxAutoAssociations = -1, tinybarBalance = 100_000_000L) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1, tinybarBalance = 100_000_000L) final SpecAccount receiver2,
            @NonNull @Contract(contract = "EmptyOne", creationGas = 100_000_000L) final SpecContract sender1,
            @NonNull @Contract(contract = "EmptyConstructor", creationGas = 100_000_000L) final SpecContract sender2,
            @NonNull @FungibleToken(initialSupply = 1_000L) final SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender1.authorizeContract(airdropContract),
                    sender2.authorizeContract(airdropContract),
                    sender1.associateTokens(token),
                    sender2.associateTokens(token),
                    token.treasury().transferUnitsTo(sender1, 100L, token),
                    token.treasury().transferUnitsTo(sender2, 100L, token));
            allRunFor(spec, checkForEmptyBalance(receiver1, List.of(token), List.of()));
            allRunFor(spec, checkForEmptyBalance(receiver2, List.of(token), List.of()));
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "tokenNAmountAirdrops",
                                    prepareTokenAddresses(spec, List.of(token, token)),
                                    prepareContractAddresses(spec, List.of(sender1, sender2)),
                                    prepareAccountAddresses(spec, List.of(receiver1, receiver2)),
                                    1L)
                            .sending(450_000_000L)
                            .gas(2_750_000L)
                            .via("pendingAirdropsMulti"),
                    getTxnRecord("pendingAirdropsMulti")
                            .hasChildRecords(recordWith().pendingAirdropsCount(0)),
                    receiver1.getBalance().andAssert(balance -> balance.hasTinyBars(100_000_000L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTinyBars(100_000_000L)),
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1L)));
        }));
    }
}
