// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The object representation of the RLP encoded peer ledger configuration received during the channel establishment.
 *
 * <p>The config payload is self-describing: no proof is carried and no signature is verified. It supplies only the
 * initial sync committee and the chain-pinning fields needed to derive the initial trust anchor, plus the advertised
 * configuration.
 *
 * @param slot                    the beacon slot the initial committee is current for; its period
 *     ({@code slot / 8192}) will pin the initial trust anchor when sync-committee rotation lands.
 * @param committee               the initial sync committee carried in the payload.
 * @param genesisValidatorsRoot32 the chain-pinning genesis validators root
 * @param forkVersion4            the fork version used for the signing domain
 * @param ledgerConfigBytes       the protobuf encoded {@code ClprLedgerConfiguration} object.
 */
record PeerLedgerConfigPayload(
        long slot,
        @NonNull SyncCommittee committee,
        @NonNull byte[] genesisValidatorsRoot32,
        @NonNull byte[] forkVersion4,
        @NonNull byte[] ledgerConfigBytes) {}
