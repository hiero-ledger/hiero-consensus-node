// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.stream.test.fixtures;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signer;
import org.hiero.consensus.event.stream.EventStreamType;
import org.hiero.consensus.event.stream.LinkedObjectStream;
import org.hiero.consensus.event.stream.RunningHashCalculatorForStream;
import org.hiero.consensus.event.stream.internal.TimestampStreamFileWriter;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Test utilities for the event stream
 */
public final class StreamUtils {
    /**
     * Writes consensus rounds to an event stream
     *
     * @param dir
     * 		the directory to write to
     * @param signer
     * 		signs the files
     * @param eventStreamWindowSize
     * 		the windows after which a new stream file will be created
     * @param rounds
     * 		the consensus rounds to write
     */
    public static void writeRoundsToStream(
            final Path dir,
            final Signer signer,
            final Duration eventStreamWindowSize,
            final Collection<ConsensusRound> rounds) {
        final LinkedObjectStream<CesEvent> stream =
                new RunningHashCalculatorForStream<>(new TimestampStreamFileWriter<>(
                        dir.toAbsolutePath().toString(),
                        eventStreamWindowSize.toMillis(),
                        signer,
                        false,
                        EventStreamType.getInstance()));
        stream.setRunningHash(new Hash(new byte[DigestType.SHA_384.digestLength()]));
        rounds.stream().flatMap(r -> r.getStreamedEvents().stream()).forEach(stream::addObject);
        stream.close();
    }
}
