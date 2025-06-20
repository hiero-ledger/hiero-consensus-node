// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.entities;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;

import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.operations.assertions.AssertNftOwnerOperation;
import com.hedera.services.bdd.spec.queries.token.HapiGetTokenNftInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;

/**
 * Represents a Hedera NFT correlated to a {@link SpecNonFungibleToken}.
 */
public class SpecNft extends AbstractSpecEntity<HapiGetTokenNftInfo, Nft> implements SpecEntity {
    private final SpecNonFungibleToken token;
    private final long serialNo;

    public SpecNft(@NonNull final SpecNonFungibleToken token, final long serialNo) {
        super(token.name() + "#" + serialNo);
        this.token = Objects.requireNonNull(token);
        this.serialNo = serialNo;
    }

    @Override
    public List<SpecEntity> prerequisiteEntities() {
        return List.of(token);
    }

    /**
     * Returns an operation asserting that this NFT is owned by the given account.
     *
     * @param owner the account
     * @return the operation
     */
    public AssertNftOwnerOperation assertOwnerIs(@NonNull final SpecAccount owner) {
        return new AssertNftOwnerOperation(this, owner);
    }

    /**
     * Returns the NFT model for the given network.
     *
     * @param network the network
     * @return the model
     */
    public Nft nftOrThrow(@NonNull final HederaNetwork network) {
        return modelOrThrow(network);
    }

    @Override
    protected Creation<HapiGetTokenNftInfo, Nft> newCreation(@NonNull final HapiSpec spec) {
        final var nftId =
                new NftID(token.tokenOrThrow(spec.targetNetworkOrThrow()).tokenIdOrThrow(), serialNo);
        return new Creation<>(
                getTokenNftInfo(token.name(), serialNo),
                Nft.newBuilder().nftId(nftId).build());
    }

    @Override
    protected Result<Nft> resultForSuccessful(
            @NonNull final Creation<HapiGetTokenNftInfo, Nft> creation, @NonNull final HapiSpec spec) {
        final var info = creation.op().getResponse().getTokenGetNftInfo().getNft();
        return new Result<>(
                creation.model()
                        .copyBuilder()
                        .metadata(Bytes.wrap(info.getMetadata().toByteArray()))
                        .mintTime(toPbj(info.getCreationTime()))
                        .ownerId(toPbj(info.getAccountID()))
                        .spenderId(toPbj(info.getSpenderId()))
                        .build(),
                siblingSpec -> {});
    }
}
