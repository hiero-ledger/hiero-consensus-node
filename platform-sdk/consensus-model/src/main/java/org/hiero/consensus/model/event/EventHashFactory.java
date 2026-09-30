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

    private static long eventCutoverMinBirthRound = -1;

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
     * Creates a Hash object from raw bytes. The type of hash created depends on the event birth round in the cutover
     * release.
     *
     * @param bytes           the raw bytes to convert
     * @param eventBirthRound the event's birth round
     * @return the constructed hash
     */
    @NonNull
    public static Hash hash(@NonNull final Bytes bytes, final long eventBirthRound) {
        if (eventCutoverMinBirthRound == -1) {
            throw new IllegalStateException("Cannot create hashes prior to initialization.");
        }
        return eventBirthRound < eventCutoverMinBirthRound
                ? new Hash(bytes, DigestType.SHA_384)
                : new Hash(bytes, DigestType.SHA_256);
    }
}
