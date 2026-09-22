// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The SSZ {@code SyncAggregate} container — the committee's vote on the attested header:
 *
 * <ul>
 *   <li>{@code bits64} — {@code Bitvector[512]} participation bitmap; bit {@code i} set means
 *       committee member {@code i} contributed a signature share.</li>
 *   <li>{@code signature96} — the BLS aggregate (G2 point sum) of exactly those members'
 *       signatures over the header's signing root.</li>
 * </ul>
 *
 * <p>The bits are implicitly authenticated by the signature: any flipped bit changes the expected
 * aggregate public key and fails the pairing check.
 */
record SyncAggregate(@NonNull byte[] bits64, @NonNull byte[] signature96) {}
