// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * One {@code [key, proofNodes[]]} storage-proof entry: {@code key} is the EVM storage slot and
 * {@code proofNodes} the Merkle-Patricia trie nodes proving its value against the account's storage root.
 */
record StorageProofEntry(@NonNull byte[] key, @NonNull byte[][] proofNodes) {}
