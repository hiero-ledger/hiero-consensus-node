// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.MIN_TRANS_TIMESTAMP_INCR_NANOS;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.crypto.CryptoConstants;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.hiero.consensus.model.crypto.Hash;

/** Various utility methods used by {@link com.swirlds.platform.ConsensusImpl} */
public final class ConsensusUtils {
    private ConsensusUtils() {}

    /**
     * Return the result of a "coin flip". It doesn't need to be cryptographicaly strong. It just
     * needs to be the case that an attacker cannot predict the coin flip results before seeing the
     * event, even if they can manipulate the internet traffic to the creator of this event earlier.
     * It's even OK if the attacker can predict the coin flip 90% of the time. There simply needs to
     * be some epsilon such that the probability of a wrong prediction is always greater than
     * epsilon (and less than 1-epsilon). This result is not memoized.
     *
     * @param event the event that will vote with a coin flip
     * @return true if voting for famous, false if voting for not famous
     */
    public static boolean coin(@NonNull final EventImpl event) {
        // coin is one bit from signature (LSB of second of two middle bytes)
        final int sigLen = (int) event.getBaseEvent().getSignature().length();
        return ((event.getBaseEvent().getSignature().getByte((sigLen / 2)) & 1) == 1);
    }

    /**
     * Calculates the minimum consensus timestamp for the next event based on current event's last
     * transaction timestamp
     *
     * @param lastTransTimestamp current event's last transaction timestamp
     * @return the minimum consensus timestamp for the next event that reaches consensus
     */
    public static @NonNull Instant calcMinTimestampForNextEvent(@NonNull final Instant lastTransTimestamp) {
        // adds minTransTimestampIncrNanos
        Instant t = lastTransTimestamp.plusNanos(MIN_TRANS_TIMESTAMP_INCR_NANOS);
        // rounds up to the nearest multiple of minTransTimestampIncrNanos
        t = t.plusNanos(MIN_TRANS_TIMESTAMP_INCR_NANOS
                - 1
                - ((MIN_TRANS_TIMESTAMP_INCR_NANOS + t.getNano() - 1) % MIN_TRANS_TIMESTAMP_INCR_NANOS));
        return t;
    }

    /**
     * @return a XOR of all judge hashes in this round
     */
    public static @NonNull byte[] generateWhitening(@NonNull final Iterable<EventImpl> judges) {
        // an XOR of the hashes of judges in a round, used during sorting
        final byte[] whitening = new byte[CryptoConstants.SIG_SIZE_BYTES];
        // find whitening for round
        for (final EventImpl w : judges) { // calculate the whitening byte array
            if (w != null) {
                final Bytes sig = w.getBaseHash().getBytes();
                final int mn = Math.min(whitening.length, (int) sig.length());
                for (int i = 0; i < mn; i++) {
                    whitening[i] ^= sig.getByte(i);
                }
            }
        }
        return whitening;
    }

    /**
     * @return a list of all base hashes of the provided events
     */
    public static @NonNull List<Bytes> getHashBytes(@NonNull final Collection<EventImpl> events) {
        return events.stream().map(EventImpl::getBaseHash).map(Hash::getBytes).toList();
    }
}
