// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.entities;

import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.operations.queries.GetTokenNftInfoOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Represents a non-fungible token that may exist on one or more target networks and be
 * registered with more than one {@link com.hedera.services.bdd.spec.HapiSpec} if desired.
 */
public class SpecNonFungibleToken extends SpecToken {
    private int numPreMints = 0;

    public SpecNonFungibleToken(@NonNull String name) {
        super(name, NON_FUNGIBLE_UNIQUE);
    }

    /**
     * Returns a representation of the requested serial number for this token.
     *
     * @param serialNo the serial number
     * @return the representation
     */
    public SpecNft serialNo(final long serialNo) {
        return new SpecNft(this, serialNo);
    }

    /**
     * Sets the number of pre-mints to perform.
     *
     * @param numPreMints the number of pre-mints to perform
     */
    public void setNumPreMints(final int numPreMints) {
        this.numPreMints = numPreMints;
    }

    /**
     * Returns an operation that retrieves the information for an NFT, identified by its serial number.
     *
     * @return the operation
     */
    public GetTokenNftInfoOperation getInfo(final int serialNumber) {
        return new GetTokenNftInfoOperation(this, serialNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<SpecOperation> postSuccessOps() {
        final List<SpecOperation> preMintOps = new ArrayList<>();
        for (int i = 0; i < numPreMints; i += Math.min(numPreMints - i, 10)) {
            preMintOps.add(mintToken(name, snMetadata(i, Math.min(i + 10, numPreMints))));
        }
        return preMintOps;
    }

    private List<ByteString> snMetadata(final int start, final int end) {
        return IntStream.range(start, end)
                .mapToObj(i -> ByteString.copyFromUtf8("SN#" + (i + 1)))
                .toList();
    }
}
