// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;

import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * The persisted trust anchor: the full current sync committee, the chain-pinning fields, and the
 * CLPR service contract address the bundle's account proof must resolve to. RLP layout is
 * {@code [syncCommittee, genesisValidatorsRoot, forkVersion, serviceAddress]}.
 *
 * <p>The committee is composed by the 512 pubkeys + aggregate, so steady-state bundles need not
 * re-transmit it. The {@code serviceAddress} (20 bytes) pins which execution-layer account the
 * bundle's MPT account proof must authenticate. Carrying it <em>in the anchor</em> makes the
 * verifier a self-contained function of {@code (bundlePayload, trustAnchor)} — no external
 * configuration is needed to verify a bundle.
 */
record TrustAnchor(
        @NonNull SyncCommittee committee,
        @NonNull byte[] genesisValidatorsRoot32,
        @NonNull byte[] forkVersion4,
        @NonNull byte[] serviceAddress20) {

    static final int SERVICE_ADDRESS_LENGTH = 20;

    /**
     * RLP-encodes a trust anchor from the full sync committee, the chain-pinning fields, and the
     * service contract address.
     */
    @NonNull
    static byte[] encode(
            @NonNull final SyncCommittee committee,
            @NonNull final byte[] genesisValidatorsRoot32,
            @NonNull final byte[] forkVersion4,
            @NonNull final byte[] serviceAddress20) {
        return Rlp.encodeList(List.of(
                committee.encode(),
                Rlp.encodeBytes(checkedCopy(genesisValidatorsRoot32, 32, "genesisValidatorsRoot")),
                Rlp.encodeBytes(checkedCopy(forkVersion4, Ssz.FORK_VERSION_LENGTH, "forkVersion")),
                Rlp.encodeBytes(checkedCopy(serviceAddress20, SERVICE_ADDRESS_LENGTH, "serviceAddress"))));
    }
}
