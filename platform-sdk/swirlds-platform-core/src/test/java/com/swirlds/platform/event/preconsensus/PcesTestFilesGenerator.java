// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A utility class for generating test PCES (Preconsensus event stream) files.
 * This class creates dummy PCES files with configurable parameters to simulate various scenarios, including
 * discontinuities, skipped files, and specific sequence number ranges.
 */
final class PcesTestFilesGenerator {
    /**
     * Range for the first sequence number, intentionally chosen to cause sequence number wrapping.
     */
    public static final Range FIRST_SEQUENCE_RANGE = new Range(950, 1000);
    /**
     * Default range for the resultingUnbrokenOrigin value.
     */
    public static final Range DEFAULT_ORIGIN_RANGE = new Range(1, 1000);
    /**
     * Range for the maximum delta between lower and upper bounds.
     */
    public static final Range MAX_DELTA_RANGE = new Range(10, 20);
    /**
     * Range for the lower bound value.
     * The lower bound is the minimum value (generation or round) that is allowed in a file.
     */
    public static final Range LOWERBOUND_RANGE = new Range(0, 1000);
    /**
     * Range for the timestamp increment in milliseconds.
     */
    public static final Range TIMESTAP_RANGE = new Range(1, 100_000);
    /**
     * Default number of files to generate.
     */
    static final int FILE_COUNT = 100;

    private final Range startingRoundRange;
    private final AncientMode ancientMode;
    private final Random rng;
    private final int count;
    private final Path fileDirectory;
    private final boolean skipSomeAtStart;
    private final boolean discontinue;
    private final boolean skipElementAtHalf;
    private final Predicate<Integer> shouldAdvanceBoundsPredicate;

    /**
     * Constructs a new PcesTestFilesGenerator.
     *
     * @param startingRoundRange           The range to generate starting round.
     * @param ancientMode                  The ancient mode to use for file generation.
     * @param rng                          The random number generator.
     * @param count                        The number of files to generate.
     * @param fileDirectory                The directory to store the generated files.
     * @param skipSomeAtStart              Whether to skip generating some files at the start.
     * @param discontinue                  Whether to introduce a discontinuity in the resultingUnbrokenOrigin value.
     * @param skipElementAtHalf            Whether to skip generating a file at the halfway point.
     * @param shouldAdvanceBoundsPredicate A predicate that tells whether to advance the bounds on the files or not.
     */
    private PcesTestFilesGenerator(
            final @Nullable Range startingRoundRange,
            final @NonNull AncientMode ancientMode,
            final @NonNull Random rng,
            final int count,
            final @NonNull Path fileDirectory,
            final boolean skipSomeAtStart,
            final boolean discontinue,
            final boolean skipElementAtHalf,
            final @Nullable Predicate<Integer> shouldAdvanceBoundsPredicate) {
        this.startingRoundRange = startingRoundRange;
        this.ancientMode = ancientMode;
        this.rng = rng;
        this.count = count;
        this.fileDirectory = fileDirectory;
        this.skipSomeAtStart = skipSomeAtStart;
        this.discontinue = discontinue;
        this.skipElementAtHalf = skipElementAtHalf;
        this.shouldAdvanceBoundsPredicate = shouldAdvanceBoundsPredicate;
    }

    /**
     * Creates a dummy {@link PcesFile} with the given descriptor.
     *
     * @param descriptor The descriptor of the file to create.
     * @throws IOException if an I/O error occurs while writing the file.
     */
    private static void createDummyFile(final @NonNull PcesFile descriptor) throws IOException {
        final Path parentDir = descriptor.getPath().getParent();
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }
        final SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(
                new FileOutputStream(descriptor.getPath().toFile()));
        out.writeInt(PcesFileVersion.currentVersionNumber());
        out.writeNormalisedString("foo bar baz");
        out.close();
    }

    /**
     * Generates PCES test files based on the configured parameters.
     *
     * @return A PcesFilesGeneratorResult containing the generated files and related information.
     * @throws IOException If an I/O error occurs.
     */
    @NonNull
    PcesFilesGeneratorResult generate() throws IOException {
        final int firstSequenceNumber = getIntFromRange(FIRST_SEQUENCE_RANGE);
        final int maxDelta = getIntFromRange(MAX_DELTA_RANGE);
        long lowerBound = getLongFromRange(LOWERBOUND_RANGE);
        long upperBound = lowerBound + rng.nextInt(2, maxDelta);

        final var nonExistentValue = lowerBound - 1;
        final long halfIndex = count / 2;

        final int startIndex = rng.nextInt(2, count - 2);

        // The index of the fileCount where the
        // discontinuity will be placed
        final int discontinuityIndex = rng.nextInt(startIndex, count);

        var timestamp = Instant.now();
        final var startingRound = startingRoundRange != null ? getLongFromRange(startingRoundRange) : 0;
        // In case we set a discontinuity, lastUnbrokenOrigin will be replaced
        var lastUnbrokenOrigin = startingRound;

        final var filesBeforeDiscontinuity = new ArrayList<PcesFile>();
        final var filesAfterDiscontinuity = new ArrayList<PcesFile>();
        final var files = new ArrayList<PcesFile>();

        for (int index = 0; index < count; index++) {
            final long sequenceNumber = firstSequenceNumber + index;
            final var isPreDiscontinuity = index < discontinuityIndex;

            // if set to intentionally introduce a discontinuity
            if (discontinue && index == discontinuityIndex) {
                lastUnbrokenOrigin = lastUnbrokenOrigin + getIntFromRange(MAX_DELTA_RANGE);
            }

            final PcesFile file = PcesFile.of(
                    ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, lastUnbrokenOrigin, fileDirectory);

            // if set to intentionally don't advance bounds
            if (shouldAdvanceBoundsPredicate == null || shouldAdvanceBoundsPredicate.test(index)) {
                lowerBound = rng.nextLong(lowerBound + 1, upperBound + 1);
                upperBound = rng.nextLong(upperBound + 1, upperBound + maxDelta);
            }
            timestamp = timestamp.plusMillis(getIntFromRange(TIMESTAP_RANGE));

            // if set to intentionally don't write a file
            if (skipElementAtHalf && index == halfIndex) {
                continue;
            }

            // it might be set to not generate some files at the beginning
            if (!skipSomeAtStart || index >= startIndex) {
                files.add(file);
                createDummyFile(file);
                if (discontinue) {
                    if (isPreDiscontinuity) {
                        filesBeforeDiscontinuity.add(file);
                    } else {
                        filesAfterDiscontinuity.add(file);
                    }
                }
            }
        }
        return new PcesFilesGeneratorResult(
                rng,
                filesBeforeDiscontinuity,
                filesAfterDiscontinuity,
                files,
                startingRound,
                lastUnbrokenOrigin,
                nonExistentValue);
    }

    /**
     * Retrieves a random long value within the specified range.
     *
     * @param range The range to generate the long value from.
     * @return A random long value within the range.
     */
    private long getLongFromRange(final @NonNull Range range) {
        return rng.nextLong(range.start(), range.end());
    }

    /**
     * Retrieves a random integer value within the specified range.
     *
     * @param range The range to generate the integer value from.
     * @return A random integer value within the range.
     */
    private int getIntFromRange(final @NonNull Range range) {
        return rng.nextInt(range.start(), range.end());
    }

    /**
     * A record representing the result of the PCES file generation process.
     *
     * @param filesBeforeDiscontinuity The list of files generated before the discontinuity.
     * @param filesAfterDiscontinuity  The list of files generated after the discontinuity.
     * @param files                    The list of all generated files.
     * @param startUnbrokenOrigin      The starting unbrokenOrigin value.
     * @param resultingUnbrokenOrigin  The final unbrokenOrigin value.
     * @param nonExistentValue         A value that does not exist in any generated file.
     */
    record PcesFilesGeneratorResult(
            @NonNull Random rng,
            @NonNull List<PcesFile> filesBeforeDiscontinuity,
            @NonNull List<PcesFile> filesAfterDiscontinuity,
            @NonNull List<PcesFile> files,
            long startUnbrokenOrigin,
            long resultingUnbrokenOrigin,
            long nonExistentValue) {

        /**
         * @return a random value placed after {@code resultingUnbrokenOrigin}
         */
        long getPointAfterUnbrokenOrigin() {
            return this.rng.nextLong(this.resultingUnbrokenOrigin() + 1, this.resultingUnbrokenOrigin() + 1000);
        }

        /**
         * @return a random value placed before {@code resultingUnbrokenOrigin} and after {@code startUnbrokenOrigin}
         */
        long getPointBeforeUnbrokenOrigin() {
            return this.rng.nextLong(this.startUnbrokenOrigin(), this.resultingUnbrokenOrigin());
        }
    }

    /**
     * A builder for creating PcesTestFilesGenerator instances.
     */
    static class Builder {
        private final AncientMode ancientMode;
        private final Random rng;
        private final Path fileDirectory;

        private Range originRange = null;
        private boolean ignoreSome;
        private boolean skipElementAtHalf;
        private boolean discontinue;
        private Predicate<Integer> shouldAdvanceBoundsPredicate;
        /**
         * Constructs a new Builder.
         *
         * @param ancientMode   The ancient mode to use
         * @param rng           The random number generator.
         * @param fileDirectory The directory to store the generated files.
         */
        private Builder(
                final @NonNull AncientMode ancientMode, final @NonNull Random rng, final @NonNull Path fileDirectory) {
            this.ancientMode = ancientMode;
            this.rng = rng;
            this.fileDirectory = fileDirectory;
        }

        /**
         * Creates a new Builder instance.
         *
         * @param ancientMode   The ancient mode to use.
         * @param rng           The random number generator.
         * @param fileDirectory The directory to store the generated files.
         * @return A new Builder instance.
         */
        @NonNull
        static Builder create(
                final @NonNull AncientMode ancientMode, final @NonNull Random rng, final @NonNull Path fileDirectory) {
            return new Builder(ancientMode, rng, fileDirectory);
        }

        /**
         * Sets the generator to introduce a discontinuity in the resultingUnbrokenOrigin value.
         *
         * @return This Builder instance.
         */
        @NonNull
        Builder discontinue() {
            discontinue = true;
            return this;
        }

        /**
         * Sets the generator to skip creating a file at the halfway point.
         *
         * @return This Builder instance.
         */
        @NonNull
        Builder skipElementAtHalf() {
            skipElementAtHalf = true;
            return this;
        }

        /**
         * Sets the generator to skip creating some files at the start.
         *
         * @return This Builder instance.
         */
        @NonNull
        Builder skipSomeAtStart() {
            ignoreSome = true;
            return this;
        }

        /**
         * Sets the generator to use the default resultingUnbrokenOrigin range.
         *
         * @return This Builder instance.
         */
        @NonNull
        Builder withDefaultOriginRange() {
            this.originRange = DEFAULT_ORIGIN_RANGE;
            return this;
        }

        /**
         * Sets the generator to use a predicate that tells when to advance the bounds for the generated files.
         *
         * @return This Builder instance.
         */
        @NonNull
        Builder withAdvanceBoundsStrategy(final @NonNull Predicate<Integer> shouldAdvanceBoundsPredicate) {
            this.shouldAdvanceBoundsPredicate = shouldAdvanceBoundsPredicate;
            return this;
        }

        /**
         * Builds a new PcesTestFilesGenerator instance.
         *
         * @return A new PcesTestFilesGenerator instance.
         */
        @NonNull
        PcesTestFilesGenerator build() {
            return new PcesTestFilesGenerator(
                    originRange,
                    ancientMode,
                    rng,
                    FILE_COUNT,
                    fileDirectory,
                    ignoreSome,
                    discontinue,
                    skipElementAtHalf,
                    shouldAdvanceBoundsPredicate);
        }
    }

    /**
     * A record representing a range of integer values.
     *
     * @param start The starting value of the range.
     * @param end   The ending value of the range.
     */
    record Range(int start, int end) {}
}
