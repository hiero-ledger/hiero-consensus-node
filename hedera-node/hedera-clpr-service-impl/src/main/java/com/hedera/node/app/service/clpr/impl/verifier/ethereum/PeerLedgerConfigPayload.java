// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * The structurally validated content of the {@code EthereumPosLedgerConfigurationPayload} received during channel
 * establishment: a light-client bootstrap (the current committee under the anchor header) and a signed update (the
 * next committee under the update header), plus the signing domain, the pinned code hash and the advertised
 * configuration.
 *
 * @param slot                    the beacon slot the current committee is current for
 * @param preset                  the consensus preset, selected by the committee size
 * @param current                 the current committee
 * @param anchorHeader            the bootstrap header the current committee is opened under
 * @param currentBranch           the branch of the current committee in {@code anchorHeader.stateRoot}
 * @param updateHeader            the header the current committee signed
 * @param syncAggregate           the current committee's signature over {@code updateHeader}
 * @param next                    the next committee
 * @param nextBranch              the branch of the next committee in {@code updateHeader.stateRoot}
 * @param genesisValidatorsRoot32 the beacon chain genesis validators root
 * @param forkVersion4            the fork version of the signing domain
 * @param codeHash32              the keccak256 of the source CLPR service contract's code
 * @param ledgerConfiguration     the advertised ledger configuration, with a 20-byte service address
 */
record PeerLedgerConfigPayload(
        long slot,
        @NonNull SyncCommitteePreset preset,
        @NonNull CommitteeKeys current,
        @NonNull BeaconHeader anchorHeader,
        @NonNull byte[][] currentBranch,
        @NonNull BeaconHeader updateHeader,
        @NonNull SyncAggregate syncAggregate,
        @NonNull CommitteeKeys next,
        @NonNull byte[][] nextBranch,
        @NonNull byte[] genesisValidatorsRoot32,
        @NonNull byte[] forkVersion4,
        @NonNull byte[] codeHash32,
        @NonNull ClprLedgerConfiguration ledgerConfiguration) {

    /**
     * A sync committee with its full member key set, as the config payload carries it.
     *
     * @param pubkeys48         the compressed member keys, in committee order
     * @param aggregatePubkey48 the compressed aggregate key
     */
    record CommitteeKeys(
            @NonNull byte[][] pubkeys48, @NonNull byte[] aggregatePubkey48) {

        /** The committee as the trust anchor holds it: the root of its keys and its aggregate. */
        @NonNull
        SyncCommittee toSyncCommittee() {
            return SyncCommittee.fromPubkeys(pubkeys48, aggregatePubkey48);
        }

        /**
         * The keys of the members that did not sign {@code syncAggregate}, in committee order, decompressed to the
         * 128-byte EIP-2537 points the BLS check takes. Read straight from the key set, with no membership proofs.
         *
         * @throws com.hedera.node.app.service.clpr.impl.verifier.ProofException if a non-signer key is not a valid
         *     compressed point of the curve
         */
        @NonNull
        List<byte[]> nonSignerPubkeys(@NonNull final SyncAggregate syncAggregate) {
            final List<byte[]> nonSignerPubkeys = new ArrayList<>();
            for (int index = 0; index < pubkeys48.length; index++) {
                if (!syncAggregate.hasSigned(index)) {
                    nonSignerPubkeys.add(
                            G1Compression.decompress(pubkeys48[index], "sync_committee.pubkeys[" + index + "]"));
                }
            }
            return nonSignerPubkeys;
        }
    }
}
