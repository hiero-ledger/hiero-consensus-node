// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * The structurally validated content of the {@code EthereumPosLedgerConfigurationPayload} received during channel
 * establishment: a light-client bootstrap (the current committee under the anchor header) and a signed update (the
 * next committee under the update header), plus the signing domain, the pinned code hash and the advertised
 * configuration.
 *
 * @param slot                    the beacon slot the current committee is current for
 * @param preset                  the consensus preset, selected by the length of the sync aggregate's bits
 * @param current                 the current committee
 * @param anchorHeader            the bootstrap header the current committee is opened under
 * @param currentBranch           the branch of the current committee in {@code anchorHeader.stateRoot}
 * @param updateHeader            the header the current committee signed
 * @param syncAggregate           the current committee's signature over {@code updateHeader}
 * @param nonSignerProofs         the proofs of the current committee's members that did not sign {@code updateHeader},
 *                                one per clear bit of {@code syncAggregate}, in committee order
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
        @NonNull SyncCommittee current,
        @NonNull BeaconHeader anchorHeader,
        @NonNull byte[][] currentBranch,
        @NonNull BeaconHeader updateHeader,
        @NonNull SyncAggregate syncAggregate,
        @NonNull List<NonSignerProof> nonSignerProofs,
        @NonNull SyncCommittee next,
        @NonNull byte[][] nextBranch,
        @NonNull byte[] genesisValidatorsRoot32,
        @NonNull byte[] forkVersion4,
        @NonNull byte[] codeHash32,
        @NonNull ClprLedgerConfiguration ledgerConfiguration) {}
