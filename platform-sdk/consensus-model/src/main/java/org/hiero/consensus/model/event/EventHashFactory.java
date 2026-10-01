// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * A temporary class used to create hash objects from raw bytes during the event hash cutover release. Events with a
 * birth round strictly lower than the threshold use SHA-384. Events with a birth round equal to or greater than the
 * threshold use SHA-256.
 */
public class EventHashFactory {

    private static long eventCutoverMinBirthRound = Long.MAX_VALUE;

    /**
     * Initializes the factory with the cutover value. If the cutover is not happening yet, the value should be
     * {@code Long.MAX_VALUE}.
     *
     * @param eventCutoverMinBirthRound the cutover birth round
     */
    public static void initialize(final long eventCutoverMinBirthRound) {
        EventHashFactory.eventCutoverMinBirthRound = eventCutoverMinBirthRound;
    }

    /**
     * Creates a Hash object from bytes. The type of hash created depends on the event birth round in the cutover
     * release.
     *
     * @param bytes           the bytes to convert
     * @param eventBirthRound the event's birth round
     * @return the constructed hash
     */
    @NonNull
    public static Hash hash(@NonNull final Bytes bytes, final long eventBirthRound) {
        return eventBirthRound < eventCutoverMinBirthRound
                ? new Hash(bytes, DigestType.SHA_384)
                : new Hash(bytes, DigestType.SHA_256);
    }

    /**
     * Creates a Hash object from raw bytes. The type of hash created depends on the length of the byte array. If the
     * length does not match a known digest type length, an exception is thrown.
     *
     * @param bytes the bytes to convert
     * @return the constructed has
     */
    @NonNull
    public static Hash hash(@NonNull final byte[] bytes) {
        final DigestType digestType = DigestType.digestLengthToDigestType(bytes.length);
        if (digestType == null) {
            throw new IllegalArgumentException(
                    String.format("No digest known digest type for length %s", bytes.length));
        }
        return new Hash(bytes, digestType);
    }

    /**
     * Creates a Hash object from bytes. The type of hash created depends on the length of the byte array. If the length
     * does not match a known digest type length, an exception is thrown.
     *
     * @param bytes the bytes to convert
     * @return the constructed has
     */
    @NonNull
    public static Hash hash(@NonNull final Bytes bytes) {
        final DigestType digestType = DigestType.digestLengthToDigestType((int) bytes.length());
        if (digestType == null) {
            throw new IllegalArgumentException(
                    String.format("No digest known digest type for length %s", bytes.length()));
        }
        return new Hash(bytes, digestType);
    }
}
