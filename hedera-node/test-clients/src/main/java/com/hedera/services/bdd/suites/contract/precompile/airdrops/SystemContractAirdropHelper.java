// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNftPendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecToken;
import com.hedera.services.bdd.spec.dsl.operations.queries.GetBalanceOperation;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

public final class SystemContractAirdropHelper {

    public static GetBalanceOperation checkForEmptyBalance(
            final SpecAccount receiver, final List<SpecFungibleToken> tokens, final List<SpecNonFungibleToken> nfts) {
        return receiver.getBalance().andAssert(balance -> {
            tokens.forEach(token -> balance.hasTokenBalance(token.name(), 0L));
            nfts.forEach(nft -> balance.hasTokenBalance(nft.name(), 0L));
        });
    }

    public static GetBalanceOperation checkForEmptyBalance(
            final SpecContract receiver, final List<SpecFungibleToken> tokens, final List<SpecNonFungibleToken> nfts) {
        return receiver.getBalance().andAssert(balance -> {
            tokens.forEach(token -> balance.hasTokenBalance(token.name(), 0L));
            nfts.forEach(nft -> balance.hasTokenBalance(nft.name(), 0L));
        });
    }

    public static Address[] prepareAccountAddresses(@NonNull HapiSpec spec, @NonNull SpecAccount... accounts) {
        return Arrays.stream(accounts)
                .map(account -> account.addressOn(spec.targetNetworkOrThrow()))
                .toArray(Address[]::new);
    }

    public static Address[] prepareAccountAddresses(@NonNull HapiSpec spec, @NonNull List<SpecAccount> accounts) {
        return accounts.stream()
                .map(account -> account.addressOn(spec.targetNetworkOrThrow()))
                .toArray(Address[]::new);
    }

    public static Address[] prepareContractAddresses(@NonNull HapiSpec spec, @NonNull SpecContract... contracts) {
        return Arrays.stream(contracts)
                .map(contract -> contract.addressOn(spec.targetNetworkOrThrow()))
                .toArray(Address[]::new);
    }

    public static Address[] prepareContractAddresses(@NonNull HapiSpec spec, @NonNull List<SpecContract> contracts) {
        return contracts.stream()
                .map(contract -> contract.addressOn(spec.targetNetworkOrThrow()))
                .toArray(Address[]::new);
    }

    public static Address[] prepareTokenAddresses(@NonNull HapiSpec spec, @NonNull SpecToken... tokens) {
        return Arrays.stream(tokens)
                .map(token -> token.addressOn(spec.targetNetworkOrThrow()))
                .toArray(Address[]::new);
    }

    public static Address[] prepareTokenAddresses(@NonNull HapiSpec spec, @NonNull List<? extends SpecToken> tokens) {
        return tokens.stream()
                .map(token -> token.addressOn(spec.targetNetworkOrThrow()))
                .toArray(Address[]::new);
    }

    public static List<TokenMovement> prepareFTAirdrops(
            @NonNull final SpecAccount sender,
            @NonNull final SpecAccount receiver,
            @NonNull final List<SpecFungibleToken> tokens) {
        return tokens.stream()
                .map(token -> moving(10, token.name()).between(sender.name(), receiver.name()))
                .toList();
    }

    public static List<TokenMovement> prepareFTAirdrops(
            @NonNull final SpecAccount sender,
            @NonNull final SpecContract receiver,
            @NonNull final List<SpecFungibleToken> tokens) {
        return tokens.stream()
                .map(token -> moving(10, token.name()).between(sender.name(), receiver.name()))
                .toList();
    }

    public static List<TokenMovement> prepareNFTAirdrops(
            @NonNull final SpecAccount sender,
            @NonNull final SpecAccount receiver,
            @NonNull final List<SpecNonFungibleToken> nfts) {
        return nfts.stream()
                .map(nft -> movingUnique(nft.name(), 1L).between(sender.name(), receiver.name()))
                .toList();
    }

    public static List<TokenMovement> prepareNFTAirdrops(
            @NonNull final SpecAccount sender,
            @NonNull final SpecContract receiver,
            @NonNull final List<SpecNonFungibleToken> nfts) {
        return nfts.stream()
                .map(nft -> movingUnique(nft.name(), 1L).between(sender.name(), receiver.name()))
                .toList();
    }

    public static GetBalanceOperation checkForBalances(
            final SpecAccount receiver, final List<SpecFungibleToken> tokens, final List<SpecNonFungibleToken> nfts) {
        return receiver.getBalance().andAssert(balance -> {
            tokens.forEach(token -> balance.hasTokenBalance(token.name(), 10L));
            nfts.forEach(nft -> balance.hasTokenBalance(nft.name(), 1L));
        });
    }

    public static GetBalanceOperation checkForBalances(
            final SpecContract receiver, final List<SpecFungibleToken> tokens, final List<SpecNonFungibleToken> nfts) {
        return receiver.getBalance().andAssert(balance -> {
            tokens.forEach(token -> balance.hasTokenBalance(token.name(), 10L));
            nfts.forEach(nft -> balance.hasTokenBalance(nft.name(), 1L));
        });
    }

    public static void prepareAirdrops(
            final SpecAccount sender,
            final SpecAccount receiver,
            @NonNull List<SpecFungibleToken> tokens,
            @NonNull List<SpecNonFungibleToken> nfts,
            @NonNull HapiSpec spec) {
        var tokenMovements = prepareFTAirdrops(sender, receiver, tokens);
        var nftMovements = prepareNFTAirdrops(sender, receiver, nfts);
        allRunFor(
                spec,
                tokenAirdrop(Stream.of(tokenMovements, nftMovements)
                                .flatMap(Collection::stream)
                                .toArray(TokenMovement[]::new))
                        .payingWith(sender.name())
                        .via("tokenAirdrop"),
                getTxnRecord("tokenAirdrop")
                        .hasPriority(recordWith()
                                .pendingAirdrops(
                                        includingFungiblePendingAirdrop(tokenMovements.toArray(TokenMovement[]::new)))
                                .pendingAirdrops(
                                        includingNftPendingAirdrop(nftMovements.toArray(TokenMovement[]::new)))));
    }

    public static void prepareAirdrops(
            final SpecAccount sender,
            final SpecContract receiver,
            @NonNull List<SpecFungibleToken> tokens,
            @NonNull List<SpecNonFungibleToken> nfts,
            @NonNull HapiSpec spec) {
        var tokenMovements = prepareFTAirdrops(sender, receiver, tokens);
        var nftMovements = prepareNFTAirdrops(sender, receiver, nfts);
        allRunFor(
                spec,
                tokenAirdrop(Stream.of(tokenMovements, nftMovements)
                                .flatMap(Collection::stream)
                                .toArray(TokenMovement[]::new))
                        .payingWith(sender.name())
                        .via("tokenAirdrop"),
                getTxnRecord("tokenAirdrop")
                        .hasPriority(recordWith()
                                .pendingAirdrops(
                                        includingFungiblePendingAirdrop(tokenMovements.toArray(TokenMovement[]::new)))
                                .pendingAirdrops(
                                        includingNftPendingAirdrop(nftMovements.toArray(TokenMovement[]::new)))));
    }

    public static SpecOperation[] prepareTokensAndBalances(
            final SpecAccount sender,
            final SpecAccount receiver,
            final List<SpecFungibleToken> tokens,
            final List<SpecNonFungibleToken> nfts) {
        ArrayList<SpecOperation> specOperations = new ArrayList<>();
        specOperations.addAll(List.of(
                sender.associateTokens(tokens.toArray(SpecFungibleToken[]::new)),
                sender.associateTokens(nfts.toArray(SpecNonFungibleToken[]::new)),
                checkForEmptyBalance(receiver, tokens, nfts)));
        specOperations.addAll(tokens.stream()
                .map(token -> token.treasury().transferUnitsTo(sender, 1_000L, token))
                .toList());
        specOperations.addAll(nfts.stream()
                .map(nft -> nft.treasury().transferNFTsTo(sender, nft, 1L))
                .toList());

        return specOperations.toArray(SpecOperation[]::new);
    }

    public static SpecOperation[] prepareTokensAndBalances(
            final SpecAccount sender,
            final SpecContract receiver,
            final List<SpecFungibleToken> tokens,
            final List<SpecNonFungibleToken> nfts) {
        ArrayList<SpecOperation> specOperations = new ArrayList<>();
        specOperations.addAll(List.of(
                sender.associateTokens(tokens.toArray(SpecFungibleToken[]::new)),
                sender.associateTokens(nfts.toArray(SpecNonFungibleToken[]::new)),
                checkForEmptyBalance(receiver, tokens, nfts)));
        specOperations.addAll(tokens.stream()
                .map(token -> token.treasury().transferUnitsTo(sender, 1_000L, token))
                .toList());
        specOperations.addAll(nfts.stream()
                .map(nft -> nft.treasury().transferNFTsTo(sender, nft, 1L))
                .toList());

        return specOperations.toArray(SpecOperation[]::new);
    }
}
