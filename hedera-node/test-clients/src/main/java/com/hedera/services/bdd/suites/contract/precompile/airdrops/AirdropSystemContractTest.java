// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareAccountAddresses;
import static com.hedera.services.bdd.suites.contract.precompile.airdrops.SystemContractAirdropHelper.prepareTokenAddresses;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableReason;
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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class AirdropSystemContractTest {

    @Contract(contract = "Airdrop", creationGas = 20_000_000L)
    static SpecContract airdropContract;

    @Account(name = "sender", tinybarBalance = 10_000_000_000L)
    static SpecAccount sender;

    @BeforeAll
    public static void beforeAll(final @NonNull TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                sender.authorizeContract(airdropContract), sender.transferHBarsTo(airdropContract, 5_000_000_000L));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Airdrop token")
    public Stream<DynamicTest> airdropToken(
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 500_000L, token),
                airdropContract
                        .call("tokenAirdrop", token, sender, receiver, 10L)
                        .gas(1500000),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 10L)));
    }

    @HapiTest
    @DisplayName("Airdrop NFT")
    public Stream<DynamicTest> airdropNft(
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft) {
        return hapiTest(
                sender.associateTokens(nft),
                nft.treasury().transferNFTsTo(sender, nft, 1L),
                airdropContract.call("nftAirdrop", nft, sender, receiver, 1L).gas(1500000),
                receiver.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)),
                nft.serialNo(1L).assertOwnerIs(receiver));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Multiple Airdrop token transactions")
    public Stream<DynamicTest> airdropTokens(
            @NonNull @FungibleToken(name = "token1", initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(name = "token2", initialSupply = 1_000_000L) final SpecFungibleToken token2,
            @NonNull @FungibleToken(name = "token3", initialSupply = 1_000_000L) final SpecFungibleToken token3,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver2,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver3) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token1, token2, token3),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2),
                    token3.treasury().transferUnitsTo(sender, 1_000L, token3));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 0L)));
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "tokenNAmountAirdrops",
                                    prepareTokenAddresses(spec, token1, token2, token3),
                                    prepareAccountAddresses(spec, sender, sender, sender),
                                    prepareAccountAddresses(spec, receiver1, receiver2, receiver3),
                                    10L)
                            .gas(1500000));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 10L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 10L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 10L)));
        }));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Multiple Airdrop NFT transactions")
    public Stream<DynamicTest> airdropNfts(
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver2,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver3) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(nft1, nft2, nft3),
                    nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                    nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                    nft3.treasury().transferNFTsTo(sender, nft3, 1L));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 0L)));
            final var serials = new long[] {1L, 1L, 1L};
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "nftNAmountAirdrops",
                                    prepareTokenAddresses(spec, nft1, nft2, nft3),
                                    prepareAccountAddresses(spec, sender, sender, sender),
                                    prepareAccountAddresses(spec, receiver1, receiver2, receiver3),
                                    serials)
                            .gas(1500000));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 1L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 1L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 1L)),
                    nft1.serialNo(1L).assertOwnerIs(receiver1),
                    nft2.serialNo(1L).assertOwnerIs(receiver2),
                    nft3.serialNo(1L).assertOwnerIs(receiver3));
        }));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Airdrop token and NFT")
    @Tag(MATS)
    public Stream<DynamicTest> airdropTokenAndNft(
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver2,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver3,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver4,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver5,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver6) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token1, token2, token3),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2),
                    token3.treasury().transferUnitsTo(sender, 1_000L, token3));
            allRunFor(
                    spec,
                    sender.associateTokens(nft1, nft2, nft3),
                    nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                    nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                    nft3.treasury().transferNFTsTo(sender, nft3, 1L));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 0L)));
            allRunFor(
                    spec,
                    receiver4.getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 0L)),
                    receiver5.getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 0L)),
                    receiver6.getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 0L)));
            final var serials = new long[] {1L, 1L, 1L};
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "mixedAirdrop",
                                    prepareTokenAddresses(spec, token1, token2, token3),
                                    prepareTokenAddresses(spec, nft1, nft2, nft3),
                                    prepareAccountAddresses(spec, sender, sender, sender),
                                    prepareAccountAddresses(spec, receiver1, receiver2, receiver3),
                                    prepareAccountAddresses(spec, sender, sender, sender),
                                    prepareAccountAddresses(spec, receiver4, receiver5, receiver6),
                                    10L,
                                    serials)
                            .gas(1750000));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 10L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 10L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 10L)),
                    receiver4.getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 1L)),
                    receiver5.getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 1L)),
                    receiver6.getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 1L)),
                    nft1.serialNo(1L).assertOwnerIs(receiver4),
                    nft2.serialNo(1L).assertOwnerIs(receiver5),
                    nft3.serialNo(1L).assertOwnerIs(receiver6));
        }));
    }

    @HapiTest
    @DisplayName("Airdrop 10 token and NFT")
    public Stream<DynamicTest> airdrop10TokenAndNft(
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token4,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token5,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft4,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft5,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver2,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver3,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver4,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver5,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver6,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver7,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver8,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver9,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver10) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token1, token2, token3, token4, token5),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2),
                    token3.treasury().transferUnitsTo(sender, 1_000L, token3),
                    token4.treasury().transferUnitsTo(sender, 1_000L, token4),
                    token5.treasury().transferUnitsTo(sender, 1_000L, token5));
            allRunFor(
                    spec,
                    sender.associateTokens(nft1, nft2, nft3, nft4, nft5),
                    nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                    nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                    nft3.treasury().transferNFTsTo(sender, nft3, 1L),
                    nft4.treasury().transferNFTsTo(sender, nft4, 1L),
                    nft5.treasury().transferNFTsTo(sender, nft5, 1L));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 0L)),
                    receiver4.getBalance().andAssert(balance -> balance.hasTokenBalance(token4.name(), 0L)),
                    receiver5.getBalance().andAssert(balance -> balance.hasTokenBalance(token5.name(), 0L)),
                    receiver6.getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 0L)),
                    receiver7.getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 0L)),
                    receiver8.getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 0L)),
                    receiver9.getBalance().andAssert(balance -> balance.hasTokenBalance(nft4.name(), 0L)),
                    receiver10.getBalance().andAssert(balance -> balance.hasTokenBalance(nft5.name(), 0L)));
            final var serials = new long[] {1L, 1L, 1L, 1L, 1L};
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "mixedAirdrop",
                                    prepareTokenAddresses(spec, token1, token2, token3, token4, token5),
                                    prepareTokenAddresses(spec, nft1, nft2, nft3, nft4, nft5),
                                    prepareAccountAddresses(spec, sender, sender, sender, sender, sender),
                                    prepareAccountAddresses(
                                            spec, receiver1, receiver2, receiver3, receiver4, receiver5),
                                    prepareAccountAddresses(spec, sender, sender, sender, sender, sender),
                                    prepareAccountAddresses(
                                            spec, receiver6, receiver7, receiver8, receiver9, receiver10),
                                    10L,
                                    serials)
                            .gas(1550000));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 10L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 10L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 10L)),
                    receiver4.getBalance().andAssert(balance -> balance.hasTokenBalance(token4.name(), 10L)),
                    receiver5.getBalance().andAssert(balance -> balance.hasTokenBalance(token5.name(), 10L)),
                    receiver6.getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 1L)),
                    receiver7.getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 1L)),
                    receiver8.getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 1L)),
                    receiver9.getBalance().andAssert(balance -> balance.hasTokenBalance(nft4.name(), 1L)),
                    receiver10.getBalance().andAssert(balance -> balance.hasTokenBalance(nft5.name(), 1L)),
                    nft1.serialNo(1L).assertOwnerIs(receiver6),
                    nft2.serialNo(1L).assertOwnerIs(receiver7),
                    nft3.serialNo(1L).assertOwnerIs(receiver8),
                    nft4.serialNo(1L).assertOwnerIs(receiver9),
                    nft5.serialNo(1L).assertOwnerIs(receiver10));
        }));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Should fail Airdrop 11 token and NFT")
    public Stream<DynamicTest> airdrop11TokenAndNft(
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token1,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token2,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token3,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token4,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token5,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft1,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft2,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft3,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft4,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft5,
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft6,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver2,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver3,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver4,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver5,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver6,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver7,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver8,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver9,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver10,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver11) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token1, token2, token3, token4, token5),
                    token1.treasury().transferUnitsTo(sender, 1_000L, token1),
                    token2.treasury().transferUnitsTo(sender, 1_000L, token2),
                    token3.treasury().transferUnitsTo(sender, 1_000L, token3),
                    token4.treasury().transferUnitsTo(sender, 1_000L, token4),
                    token5.treasury().transferUnitsTo(sender, 1_000L, token5));
            allRunFor(
                    spec,
                    sender.associateTokens(nft1, nft2, nft3, nft4, nft5, nft6),
                    nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                    nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                    nft3.treasury().transferNFTsTo(sender, nft3, 1L),
                    nft4.treasury().transferNFTsTo(sender, nft4, 1L),
                    nft5.treasury().transferNFTsTo(sender, nft5, 1L),
                    nft6.treasury().transferNFTsTo(sender, nft6, 1L));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 0L)),
                    receiver4.getBalance().andAssert(balance -> balance.hasTokenBalance(token4.name(), 0L)),
                    receiver5.getBalance().andAssert(balance -> balance.hasTokenBalance(token5.name(), 0L)),
                    receiver6.getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 0L)),
                    receiver7.getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 0L)),
                    receiver8.getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 0L)),
                    receiver9.getBalance().andAssert(balance -> balance.hasTokenBalance(nft4.name(), 0L)),
                    receiver10.getBalance().andAssert(balance -> balance.hasTokenBalance(nft5.name(), 0L)),
                    receiver11.getBalance().andAssert(balance -> balance.hasTokenBalance(nft6.name(), 0L)));
            final var serials = new long[] {1L, 1L, 1L, 1L, 1L, 1L};
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "mixedAirdrop",
                                    prepareTokenAddresses(spec, token1, token2, token3, token4, token5),
                                    prepareTokenAddresses(spec, nft1, nft2, nft3, nft4, nft5),
                                    prepareAccountAddresses(spec, sender, sender, sender, sender, sender),
                                    prepareAccountAddresses(
                                            spec, receiver1, receiver2, receiver3, receiver4, receiver5),
                                    prepareAccountAddresses(spec, sender, sender, sender, sender, sender, sender),
                                    prepareAccountAddresses(
                                            spec, receiver6, receiver7, receiver8, receiver9, receiver10, receiver11),
                                    10L,
                                    serials)
                            .gas(1550000)
                            .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED)));
            allRunFor(
                    spec,
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 0L)),
                    receiver4.getBalance().andAssert(balance -> balance.hasTokenBalance(token4.name(), 0L)),
                    receiver5.getBalance().andAssert(balance -> balance.hasTokenBalance(token5.name(), 0L)),
                    receiver6.getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 0L)),
                    receiver7.getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 0L)),
                    receiver8.getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 0L)),
                    receiver9.getBalance().andAssert(balance -> balance.hasTokenBalance(nft4.name(), 0L)),
                    receiver10.getBalance().andAssert(balance -> balance.hasTokenBalance(nft5.name(), 0L)),
                    receiver11.getBalance().andAssert(balance -> balance.hasTokenBalance(nft6.name(), 0L)));
        }));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Airdrop fails when the sender does not have enough balance")
    public Stream<DynamicTest> airdropFailsWhenSenderDoesNotHaveEnoughBalance(
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token) {
        return hapiTest(
                sender.associateTokens(token),
                airdropContract
                        .call("tokenAirdrop", token, sender, receiver, 10L)
                        .gas(1500000)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INSUFFICIENT_TOKEN_BALANCE)));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Airdrop fails when the receiver does not have a valid account")
    public Stream<DynamicTest> airdropFailsWhenReceiverDoesNotHaveValidAccount(
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken token,
            @NonNull @FungibleToken(initialSupply = 1_000_000L) final SpecFungibleToken tokenAsReceiver) {
        return hapiTest(
                sender.associateTokens(token),
                token.treasury().transferUnitsTo(sender, 500_000L, token),
                airdropContract
                        .call("tokenAirdrop", token, sender, tokenAsReceiver, 10L)
                        .gas(1500000)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_ACCOUNT_ID)));
    }

    @HapiTest
    @DisplayName("Airdrop fails when the token does not exist")
    public Stream<DynamicTest> airdropFailsWhenTokenDoesNotExist(
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver,
            @NonNull @Account final SpecAccount accountAsToken) {
        return hapiTest(airdropContract
                .call("tokenAirdrop", accountAsToken, sender, receiver, 10L)
                .gas(1500000)
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)));
    }

    @HapiTest
    @DisplayName("Airdrop fails with nft serials out of bound")
    public Stream<DynamicTest> failToUpdateNFTsMetadata(
            @NonNull @NonFungibleToken(numPreMints = 1) final SpecNonFungibleToken nft,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver) {
        return hapiTest(
                sender.associateTokens(nft),
                airdropContract
                        .call("nftAirdrop", nft, sender, receiver, Long.MAX_VALUE)
                        .gas(1500000)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_NFT_ID)),
                airdropContract
                        .call("nftAirdrop", nft, sender, receiver, 0L)
                        .gas(1500000)
                        .andAssert(
                                txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_NFT_SERIAL_NUMBER)),
                airdropContract
                        .call("nftAirdrop", nft, sender, receiver, -1L)
                        .gas(1500000)
                        .andAssert(txn ->
                                txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_NFT_SERIAL_NUMBER)));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Distribute NFTs to multiple accounts")
    @Tag(MATS)
    public Stream<DynamicTest> distributeNfts(
            @NonNull @NonFungibleToken(numPreMints = 3) final SpecNonFungibleToken nft,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver2,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver3) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(nft),
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver1.getInfo(),
                    receiver1.getInfo(),
                    receiver3.getInfo(),
                    nft.treasury().transferNFTsTo(sender, nft, 1L, 2L, 3L));
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "nftAirdropDistribute",
                                    nft,
                                    sender,
                                    prepareAccountAddresses(spec, receiver1, receiver2, receiver3))
                            .gas(1500000),
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)),
                    nft.serialNo(1L).assertOwnerIs(receiver1),
                    nft.serialNo(2L).assertOwnerIs(receiver2),
                    nft.serialNo(3L).assertOwnerIs(receiver3));
        }));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Cannot Distribute 11 NFTs to multiple accounts")
    public Stream<DynamicTest> distributeNftsOutOfBound(
            @NonNull @NonFungibleToken(numPreMints = 11) final SpecNonFungibleToken nft,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver2,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver3,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver4,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver5,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver6,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver7,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver8,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver9,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver10,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver11) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(nft),
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver4.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver5.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver6.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver7.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver8.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver9.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver10.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver11.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    nft.treasury().transferNFTsTo(sender, nft, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L),
                    nft.treasury().transferNFTsTo(sender, nft, 11L));
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "nftAirdropDistribute",
                                    nft,
                                    sender,
                                    prepareAccountAddresses(
                                            spec,
                                            receiver1,
                                            receiver2,
                                            receiver3,
                                            receiver4,
                                            receiver5,
                                            receiver6,
                                            receiver7,
                                            receiver8,
                                            receiver9,
                                            receiver10,
                                            receiver11))
                            .andAssert(txn -> txn.hasKnownStatuses(
                                    CONTRACT_REVERT_EXECUTED, TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED))
                            .gas(1500000),
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver4.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver5.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver6.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver7.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver8.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver9.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)));
        }));
    }

    @HapiTest
    @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Cannot distribute NFTs to multiple accounts when some of the NFTs do not exist")
    public Stream<DynamicTest> failToDistributeNfts(
            @NonNull @NonFungibleToken(numPreMints = 6) final SpecNonFungibleToken nft,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver1,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver2,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver3,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver4,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver5,
            @NonNull @Account(maxAutoAssociations = -1) final SpecAccount receiver6) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(nft),
                    // We have six pre minted serials
                    // Burning some of them to make them invalid
                    burnToken(nft.name(), List.of(3L, 4L)),
                    receiver1.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver2.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver3.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver4.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver5.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver6.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    receiver1.getInfo(),
                    receiver1.getInfo(),
                    receiver3.getInfo(),
                    receiver4.getInfo(),
                    receiver5.getInfo(),
                    receiver6.getInfo(),
                    nft.treasury().transferNFTsTo(sender, nft, 1L, 2L, 5L, 6L));
            allRunFor(
                    spec,
                    airdropContract
                            .call(
                                    "nftAirdropDistribute",
                                    nft,
                                    sender,
                                    prepareAccountAddresses(
                                            spec, receiver1, receiver2, receiver3, receiver4, receiver5, receiver6))
                            .gas(1500000)
                            .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_NFT_ID)));
        }));
    }
}
