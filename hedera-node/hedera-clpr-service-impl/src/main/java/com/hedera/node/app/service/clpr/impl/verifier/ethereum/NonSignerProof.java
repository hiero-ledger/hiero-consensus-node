// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The key of a committee member that did not sign, with its SSZ inclusion branch under the committee's
 * {@code pubkeysRoot}. The member's committee index is not carried: it is the position of the matching clear bit.
 * Both bundles and config payloads carry these proofs.
 *
 * @param pubkey128 the member's key, a 128-byte EIP-2537 uncompressed G1 point
 * @param branch    the sibling hashes from the key's leaf up to {@code pubkeysRoot}, bottom-up
 */
record NonSignerProof(@NonNull byte[] pubkey128, @NonNull byte[][] branch) {}
