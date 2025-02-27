// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignaturePair;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;

/**
 * Expands {@link SignaturePair}s into {@link ExpandedSignaturePair}s.
 *
 * <p>A {@link SignaturePair} is a cryptographic signature and an optional public key prefix. The prefix can be anything
 * from 0 bytes all the way up to the bytes of a full cryptographic public key. Only ED25519 (32 bytes long) and
 * ECDSA_SECP256K1 <strong>compressed</strong> (33 bytes long) public keys are supported.
 *
 * <p>The job of the {@link SignatureExpander} is to "expand" the prefix of a {@link SignaturePair} to be a full,
 * uncompressed (in the case of ECDSA_SECP256K1), public key. There are two different scenarios it must deal with:
 * <ol>
 *     <li>Given a {@link Key}, find a {@link SignaturePair} with a signature type and prefix that matches the
 *     cryptographic keys that make up the {@link Key} (remember a {@link Key} could be a compound key such as a
 *     {@link com.hedera.hapi.node.base.KeyList} or a {@link com.hedera.hapi.node.base.ThresholdKey}).</li>
 *
 *     <li>Any key with a "full" prefix will be expanded. If that full prefix belongs to an ECDSA_SECP256K1 key, then
 *     also compute the 20-byte evm alias based on the decompressed bytes of the prefix.</li>
 * </ol>
 */
public interface SignatureExpander {
    /**
     * Given a list of {@link SignaturePair}s, find all that have a full key prefix, uncompressing any ECDSA_SECP256K1
     * keys, and populate the given set of {@code expanded} pairs. Note these expanded pairs also have the EVM alias
     * if the expanded pair is for an ECDSA(secp256k1) key.
     *
     * @param sigPairs The {@link SignaturePair}s to search for full key prefixes. This list must be pre-filtered such
     *                 that there are no duplicate entries and one prefix is not the prefix of another.
     * @param expanded Will be populated with all created {@link ExpandedSignaturePair}s
     */
    void expand(@NonNull List<SignaturePair> sigPairs, @NonNull Set<ExpandedSignaturePair> expanded);

    /**
     * Given a {@link Key}, traverses it looking for each cryptographic key (ED25519, ECDSA_SECP256K1). For each such
     * cryptographic key, look for a corresponding matching prefix in the {@link SignaturePair}s. If one is found, then
     * we are guaranteed that it is the only possible match, and a corresponding {@link ExpandedSignaturePair} will be
     * created.
     *
     * @param key The {@link Key} to traverse looking for cryptographic keys, used to find matching
     *            {@link SignaturePair}s
     * @param sigPairs The {@link SignaturePair}s to search for full key prefixes. This list must be pre-filtered such
     *                 that there are no duplicate entries and one prefix is not the prefix of another.
     * @param expanded Will be populated with all created {@link ExpandedSignaturePair}s
     */
    void expand(@NonNull Key key, @NonNull List<SignaturePair> sigPairs, @NonNull Set<ExpandedSignaturePair> expanded);

    /**
     * Expands all {@link Key}s within an {@link Iterable}.
     *
     * @param keys The {@link Iterable} of keys
     * @param sigPairs The {@link SignaturePair}s to search for full key prefixes. This list must be pre-filtered such
     *                 that there are no duplicate entries and one prefix is not the prefix of another.
     * @param expanded Will be populated with all created {@link ExpandedSignaturePair}s
     */
    default void expand(
            @NonNull Iterable<Key> keys,
            @NonNull List<SignaturePair> sigPairs,
            @NonNull Set<ExpandedSignaturePair> expanded) {
        for (final var key : keys) {
            expand(key, sigPairs, expanded);
        }
    }
}
