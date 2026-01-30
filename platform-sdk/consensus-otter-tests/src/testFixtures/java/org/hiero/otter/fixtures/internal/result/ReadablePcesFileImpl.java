// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.impl.common.PcesFile;
import org.hiero.otter.fixtures.result.ReadablePcesFile;

/**
 * The default implementation of {@link ReadablePcesFile}.
 */
public class ReadablePcesFileImpl implements ReadablePcesFile {

    private final PcesFile pcesFile;

    /**
     * Construct a new ReadablePcesFile.
     *
     * @param pcesFile the underlying {@link PcesFile}
     */
    ReadablePcesFileImpl(@NonNull final PcesFile pcesFile) {
        this.pcesFile = requireNonNull(pcesFile);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Instant timestamp() {
        return pcesFile.getTimestamp();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long sequenceNumber() {
        return pcesFile.getSequenceNumber();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long lowerBound() {
        return pcesFile.getLowerBound();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long upperBound() {
        return pcesFile.getUpperBound();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long origin() {
        return pcesFile.getOrigin();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Path path() {
        return pcesFile.getPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String fileName() {
        return pcesFile.getFileName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canContain(final long eventSequenceNumber) {
        return pcesFile.canContain(eventSequenceNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public IOIterator<PlatformEvent> iterator(final long lowerBound) throws IOException {
        return pcesFile.iterator(lowerBound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String toString() {
        return pcesFile.toString();
    }
}
