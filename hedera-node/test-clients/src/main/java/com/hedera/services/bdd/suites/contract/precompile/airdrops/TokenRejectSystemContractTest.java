// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class TokenRejectSystemContractTest {

    @Contract(contract = "TokenReject", creationGas = 1_000_000L)
    static SpecContract tokenReject;

    @Account(tinybarBalance = 1_000_000_000L)
    static SpecAccount sender;

    @BeforeAll
    public static void setup(final @NonNull TestLifecycle lifecycle) {
        lifecycle.doAdhoc(sender.authorizeContract(tokenReject));
    }

    @HapiTest
    @DisplayName("Reject fungible token")
    public Stream<DynamicTest> tokenRejectSystemContractTest(
            @FungibleToken(initialSupply = 1000) SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token),
                    token.treasury().transferUnitsTo(sender, 100, token),
                    token.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 900L)));
            final var tokenAddress = token.addressOn(spec.targetNetworkOrThrow());
            allRunFor(
                    spec,
                    tokenReject.call("rejectTokens", sender, new Address[] {tokenAddress}, new Address[0]),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 0L)),
                    token.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token.name(), 1000L)));
        }));
    }

    @HapiTest
    @DisplayName("Reject non-fungible token")
    public Stream<DynamicTest> tokenRejectSystemContractNftTest(
            @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(nft),
                    nft.treasury().transferNFTsTo(sender, nft, 1L),
                    nft.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)));
            final var tokenAddress = nft.addressOn(spec.targetNetworkOrThrow());
            allRunFor(
                    spec,
                    tokenReject.call("rejectTokens", sender, new Address[] {}, new Address[] {tokenAddress}),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 0L)),
                    nft.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(nft.name(), 1L)));
        }));
    }

    @HapiTest
    @DisplayName("Reject multiple tokens")
    public Stream<DynamicTest> tokenRejectForMultipleTokens(
            @FungibleToken SpecFungibleToken token1,
            @FungibleToken SpecFungibleToken token2,
            @FungibleToken SpecFungibleToken token3,
            @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft1,
            @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft2,
            @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken nft3) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(token1, token2, token3, nft1, nft2, nft3),
                    token1.treasury().transferUnitsTo(sender, 100, token1),
                    token2.treasury().transferUnitsTo(sender, 100, token2),
                    token3.treasury().transferUnitsTo(sender, 100, token3),
                    nft1.treasury().transferNFTsTo(sender, nft1, 1L),
                    nft2.treasury().transferNFTsTo(sender, nft2, 1L),
                    nft3.treasury().transferNFTsTo(sender, nft3, 1L));
            final var token1Address = token1.addressOn(spec.targetNetworkOrThrow());
            final var token2Address = token2.addressOn(spec.targetNetworkOrThrow());
            final var token3Address = token3.addressOn(spec.targetNetworkOrThrow());
            final var nft1Address = nft1.addressOn(spec.targetNetworkOrThrow());
            final var nft2Address = nft2.addressOn(spec.targetNetworkOrThrow());
            final var nft3Address = nft3.addressOn(spec.targetNetworkOrThrow());
            allRunFor(
                    spec,
                    tokenReject
                            .call(
                                    "rejectTokens",
                                    sender,
                                    new Address[] {token1Address, token2Address, token3Address},
                                    new Address[] {nft1Address, nft2Address, nft3Address})
                            .gas(1_000_000L),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 0L)),
                    token1.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token1.name(), 100L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 0L)),
                    token2.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token2.name(), 100L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 0L)),
                    token3.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(token3.name(), 100L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 0L)),
                    nft1.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(nft1.name(), 1L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 0L)),
                    nft2.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(nft2.name(), 1L)),
                    sender.getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 0L)),
                    nft3.treasury().getBalance().andAssert(balance -> balance.hasTokenBalance(nft3.name(), 1L)));
        }));
    }

    @HapiTest
    @DisplayName("Fails to reject tokens if limits exceeded")
    @Tag(MATS)
    public Stream<DynamicTest> failsIfLimitsExceeded(
            @FungibleToken SpecFungibleToken token1,
            @FungibleToken SpecFungibleToken token2,
            @FungibleToken SpecFungibleToken token3,
            @FungibleToken SpecFungibleToken token4,
            @FungibleToken SpecFungibleToken token5,
            @FungibleToken SpecFungibleToken token6,
            @FungibleToken SpecFungibleToken token7,
            @FungibleToken SpecFungibleToken token8,
            @FungibleToken SpecFungibleToken token9,
            @FungibleToken SpecFungibleToken token10,
            @FungibleToken SpecFungibleToken token11) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    sender.associateTokens(
                            token1, token2, token3, token4, token5, token6, token7, token8, token9, token10, token11),
                    token1.treasury().transferUnitsTo(sender, 100, token1),
                    token2.treasury().transferUnitsTo(sender, 100, token2),
                    token3.treasury().transferUnitsTo(sender, 100, token3),
                    token4.treasury().transferUnitsTo(sender, 100, token4),
                    token5.treasury().transferUnitsTo(sender, 100, token5),
                    token6.treasury().transferUnitsTo(sender, 100, token6),
                    token7.treasury().transferUnitsTo(sender, 100, token7),
                    token8.treasury().transferUnitsTo(sender, 100, token8),
                    token9.treasury().transferUnitsTo(sender, 100, token9),
                    token10.treasury().transferUnitsTo(sender, 100, token10),
                    token11.treasury().transferUnitsTo(sender, 100, token11));
            final var token1Address = token1.addressOn(spec.targetNetworkOrThrow());
            final var token2Address = token2.addressOn(spec.targetNetworkOrThrow());
            final var token3Address = token3.addressOn(spec.targetNetworkOrThrow());
            final var token4Address = token4.addressOn(spec.targetNetworkOrThrow());
            final var token5Address = token5.addressOn(spec.targetNetworkOrThrow());
            final var token6Address = token6.addressOn(spec.targetNetworkOrThrow());
            final var token7Address = token7.addressOn(spec.targetNetworkOrThrow());
            final var token8Address = token8.addressOn(spec.targetNetworkOrThrow());
            final var token9Address = token9.addressOn(spec.targetNetworkOrThrow());
            final var token10Address = token10.addressOn(spec.targetNetworkOrThrow());
            final var token11Address = token11.addressOn(spec.targetNetworkOrThrow());
            allRunFor(
                    spec,
                    tokenReject
                            .call(
                                    "rejectTokens",
                                    sender,
                                    new Address[] {
                                        token1Address,
                                        token2Address,
                                        token3Address,
                                        token4Address,
                                        token5Address,
                                        token6Address,
                                        token7Address,
                                        token8Address,
                                        token9Address,
                                        token10Address,
                                        token11Address
                                    },
                                    new Address[0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }));
    }

    @HapiTest
    @DisplayName("Fails to reject tokens if there are no associated tokens")
    @Tag(MATS)
    public Stream<DynamicTest> failsIfNoAssociatedTokens(@FungibleToken SpecFungibleToken token) {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, sender.associateTokens(token));
            final var tokenAddress = token.addressOn(spec.targetNetworkOrThrow());
            allRunFor(
                    spec,
                    tokenReject
                            .call("rejectTokens", sender, new Address[] {tokenAddress}, new Address[0])
                            .gas(1_000_000L)
                            .andAssert(
                                    txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INSUFFICIENT_TOKEN_BALANCE)));
        }));
    }

    @HapiTest
    @DisplayName("Fails if token is invalid")
    public Stream<DynamicTest> failsIfTokenIsInvalid() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var senderAddress = sender.addressOn(spec.targetNetworkOrThrow());
            allRunFor(
                    spec,
                    tokenReject
                            .call("rejectTokens", sender, new Address[] {senderAddress}, new Address[0])
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)));
        }));
    }

    @HapiTest
    @DisplayName("Fails if NFT is invalid")
    public Stream<DynamicTest> failsIfNFTIsInvalid() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var senderAddress = sender.addressOn(spec.targetNetworkOrThrow());
            allRunFor(
                    spec,
                    tokenReject
                            .call("rejectTokens", sender, new Address[] {}, new Address[] {senderAddress})
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_NFT_ID)));
        }));
    }
}
