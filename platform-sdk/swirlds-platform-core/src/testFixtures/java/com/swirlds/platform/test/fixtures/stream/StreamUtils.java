// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.stream;

import com.swirlds.common.stream.EventStreamType;
import com.swirlds.common.stream.RunningHashCalculatorForStream;
import com.swirlds.common.stream.Signer;
import com.swirlds.common.stream.internal.LinkedObjectStream;
import com.swirlds.common.stream.internal.TimestampStreamFileWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;
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
