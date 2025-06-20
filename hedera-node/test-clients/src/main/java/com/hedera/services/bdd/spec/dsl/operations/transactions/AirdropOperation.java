// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TokenType;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.OwningEntity;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecToken;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAirdrop;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

public class AirdropOperation extends AbstractSpecTransaction<AirdropOperation, HapiTokenAirdrop>
        implements SpecOperation {
    public record Airdrop(
            @Nullable SpecFungibleToken fungibleToken,
            @Nullable SpecNonFungibleToken nonFungibleToken,
            @NonNull OwningEntity receiver,
            long asset,
            @NonNull AliasUsage aliasUsage) {
        public enum AliasUsage {
            NEITHER,
            RECEIVER,
            SENDER,
            BOTH
        }

        public static Airdrop forFungible(
                @NonNull final SpecFungibleToken token, @NonNull final OwningEntity receiver, final long amount) {
            return forFungible(token, receiver, amount, AliasUsage.NEITHER);
        }

        public static Airdrop forFungible(
                @NonNull final SpecFungibleToken token,
                @NonNull final OwningEntity receiver,
                final long amount,
                @NonNull final AliasUsage aliasUsage) {
            requireNonNull(token);
            requireNonNull(receiver);
            return new Airdrop(token, null, receiver, amount, aliasUsage);
        }

        public static Airdrop forNonFungibleToken(
                @NonNull final SpecNonFungibleToken token, @NonNull final OwningEntity receiver, final long serialNo) {
            return forNonFungibleToken(token, receiver, serialNo, AliasUsage.NEITHER);
        }

        public static Airdrop forNonFungibleToken(
                @NonNull final SpecNonFungibleToken token,
                @NonNull final OwningEntity receiver,
                final long serialNo,
                @NonNull final AliasUsage aliasUsage) {
            requireNonNull(token);
            requireNonNull(receiver);
            return new Airdrop(null, token, receiver, serialNo, aliasUsage);
        }

        public Stream<SpecEntity> entities() {
            return Stream.of(token(), receiver);
        }

        public TokenType type() {
            return fungibleToken != null ? TokenType.FUNGIBLE_COMMON : TokenType.NON_FUNGIBLE_UNIQUE;
        }

        public SpecNonFungibleToken nonFungibleTokenOrThrow() {
            return requireNonNull(nonFungibleToken);
        }

        public SpecFungibleToken fungibleTokenOrThrow() {
            return requireNonNull(fungibleToken);
        }

        public TokenMovement asMovementFrom(@NonNull final SpecAccount sender) {
            final var builder =
                    switch (type()) {
                        case FUNGIBLE_COMMON ->
                            TokenMovement.moving(asset, fungibleTokenOrThrow().name());
                        case NON_FUNGIBLE_UNIQUE ->
                            TokenMovement.movingUnique(nonFungibleTokenOrThrow().name(), asset);
                    };
            return switch (aliasUsage) {
                case NEITHER -> builder.between(sender.name(), receiver.name());
                case RECEIVER -> builder.between(sender.name(), asLongZeroAddress(receiver.name()));
                case SENDER -> builder.between(asLongZeroAddress(sender.name()), receiver.name());
                case BOTH -> builder.between(asLongZeroAddress(sender.name()), asLongZeroAddress(receiver.name()));
            };
        }

        private static Function<HapiSpec, String> asLongZeroAddress(@NonNull final String account) {
            return spec ->
                    idAsHeadlongAddress(spec.registry().getAccountID(account)).toString();
        }

        private @NonNull SpecToken token() {
            return fungibleToken != null ? fungibleToken : requireNonNull(nonFungibleToken);
        }
    }

    private final SpecAccount sender;
    private final List<Airdrop> airdrops;

    public AirdropOperation(@NonNull final SpecAccount sender, @NonNull final List<Airdrop> airdrops) {
        super(Stream.concat(Stream.of(sender), airdrops.stream().flatMap(Airdrop::entities))
                .toList());
        this.sender = requireNonNull(sender);
        this.airdrops = requireNonNull(airdrops);
    }

    @Override
    protected AirdropOperation self() {
        return this;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        final var movements =
                airdrops.stream().map(airdrop -> airdrop.asMovementFrom(sender)).toArray(TokenMovement[]::new);
        final var op = tokenAirdrop(movements).payingWith(sender.name());
        if (airdrops.stream().anyMatch(a -> a.aliasUsage() != Airdrop.AliasUsage.NEITHER)) {
            // Default to explicitly signing with just the sender if using alias references,
            // since registry doesn't yet support looking up keys by long-zero address
            op.signedBy(sender.name());
        }
        maybeAssertions().ifPresent(a -> a.accept(op));
        return op;
    }
}
