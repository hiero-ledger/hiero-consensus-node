// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

/**
 * The fork-specific constants of the Gloas fork (EIP-7732), the only fork this verifier supports: the generalized
 * indices of the branches it walks. A fork that changes the {@code BeaconState} or {@code BeaconBlockBody} layout
 * moves them, so each value keeps its spec name.
 *
 * <p>Gloas also changes how the execution state is proven: the block body commits to the execution block hash, not to
 * its state root, so the verifier reads the state root from the EVM block header (see
 * {@code EthereumSyncCommitteeProofVerifier}).
 *
 * @see <a href="https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/light-client/sync-protocol.md">
 *     Gloas light-client sync protocol</a>
 */
final class Gloas {

    /** {@code FINALIZED_ROOT_GINDEX_GLOAS}: {@code BeaconState.finalized_checkpoint.root} under the state root. */
    static final GeneralizedIndex FINALIZED_ROOT = new GeneralizedIndex(735);

    /** {@code CURRENT_SYNC_COMMITTEE_GINDEX_GLOAS}: {@code BeaconState.current_sync_committee} under the state root. */
    static final GeneralizedIndex CURRENT_SYNC_COMMITTEE = new GeneralizedIndex(2945);

    /** {@code NEXT_SYNC_COMMITTEE_GINDEX_GLOAS}: {@code BeaconState.next_sync_committee} under the state root. */
    static final GeneralizedIndex NEXT_SYNC_COMMITTEE = new GeneralizedIndex(2946);

    /**
     * {@code EXECUTION_BLOCK_HASH_GINDEX_GLOAS}: {@code BeaconBlockBody.signed_execution_payload_bid.message
     * .parent_block_hash} under the block body root.
     */
    static final GeneralizedIndex EXECUTION_BLOCK_HASH = new GeneralizedIndex(2856);

    private Gloas() {}
}
