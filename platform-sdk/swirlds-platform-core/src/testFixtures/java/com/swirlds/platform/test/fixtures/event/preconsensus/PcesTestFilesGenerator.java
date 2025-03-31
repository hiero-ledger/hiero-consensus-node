// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.preconsensus;

import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.platform.event.preconsensus.PcesFile;
import com.swirlds.platform.event.preconsensus.PcesFileVersion;
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
public final class PcesTestFilesGenerator {
    /**
     * Range for the first sequence number, intentionally chosen to cause sequence number wrapping.
     */
    public static final Range FIRST_SEQUENCE_RANGE = new Range(950, 1000);
    /**
     * Range for the maximum delta between lower and upper bounds values (generation or round)
     * that indicates what's allowed to be stored in a Pces file.
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
    public static final int DEFAULT_NUM_FILES_TO_GENERATE = 100;

    private final Range startingOriginRange;
    private final AncientMode ancientMode;
    private final Random rng;
    private final int numFilesToGenerate;
    private final Path fileDirectory;
    private final boolean skipAtStart;
    private final boolean discontinue;
    private final boolean skipElementAtHalf;
    private final Predicate<Integer> shouldAdvanceBoundsPredicate;

    /**
     * Constructs a new PcesTestFilesGenerator.
     *
     * @param startingOriginRange The range to generate origin value.
     * @param ancientMode The ancient mode to use for file generation.
     * @param rng The random number generator.
     * @param numFilesToGenerate The number of files to generate.
     * @param fileDirectory The directory to store the generated files.
     * @param skipAtStart Whether to skip generating some files at the beginning of the list.
     * @param discontinue Whether to introduce a discontinuity in the origin value.
     * @param skipElementAtHalf Whether to skip generating a file at the halfway point.
     * @param shouldAdvanceBoundsPredicate A predicate that tells whether to advance the bounds on the files or not.
     */
    private PcesTestFilesGenerator(
            final @Nullable Range startingOriginRange,
            final @NonNull AncientMode ancientMode,
            final @NonNull Random rng,
            final int numFilesToGenerate,
            final @NonNull Path fileDirectory,
            final boolean skipAtStart,
            final boolean discontinue,
            final boolean skipElementAtHalf,
            final @Nullable Predicate<Integer> shouldAdvanceBoundsPredicate) {
        this.startingOriginRange = startingOriginRange;
        this.ancientMode = ancientMode;
        this.rng = rng;
        this.numFilesToGenerate = numFilesToGenerate;
        this.fileDirectory = fileDirectory;
        this.skipAtStart = skipAtStart;
        this.discontinue = discontinue;
        this.skipElementAtHalf = skipElementAtHalf;
        this.shouldAdvanceBoundsPredicate = shouldAdvanceBoundsPredicate;
    }

    /**
     * Writes to disk a {@link PcesFile} with the given descriptor and dummy content.
     *
     * @param descriptor The descriptor of the file to create.
     * @throws IOException if an I/O error occurs while writing the file.
     */
    private static void createDummyPcesFile(final @NonNull PcesFile descriptor) throws IOException {
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
     * The files are written to disk to the configured location.
     *
     * @return A PcesFilesGeneratorResult containing the generated files and related information.
     * @throws IOException If an I/O error occurs.
     */
    @NonNull
    public PcesFilesGeneratorResult generate() throws IOException {
        final int firstSequenceNumber = getIntFromRange(FIRST_SEQUENCE_RANGE);
        final int maxDelta = getIntFromRange(MAX_DELTA_RANGE);
        long lowerBound = getLongFromRange(LOWERBOUND_RANGE);
        // First lower bound needs to leave an space to get a valid nonExistentValue
        long upperBound = lowerBound + rng.nextInt(2, maxDelta);

        final long nonExistentValue = lowerBound + 1;
        final long halfIndex = numFilesToGenerate / 2;

        if ((discontinue || skipAtStart) && numFilesToGenerate < 5) {
            throw new IllegalArgumentException("if discontinue or skipStart is set the minimum files to generate is 5");
        }

        final int startIndexIfSkip = discontinue || skipAtStart ? rng.nextInt(2, numFilesToGenerate - 2) : 0;

        // The index of the fileCount where the discontinuity (if set) will be placed
        final int discontinuityIndex = rng.nextInt(startIndexIfSkip, numFilesToGenerate);

        Instant timestamp = Instant.now();
        final long originStartValue = startingOriginRange != null ? getLongFromRange(startingOriginRange) : 0;
        // In case we set a discontinuity, originCurrentValue will be replaced
        long originCurrentValue = originStartValue;

        final List<PcesFile> filesBeforeDiscontinuity = new ArrayList<>();
        final List<PcesFile> filesAfterDiscontinuity = new ArrayList<>();
        final List<PcesFile> files = new ArrayList<>();

        for (int index = 0; index < numFilesToGenerate; index++) {
            final long sequenceNumber = firstSequenceNumber + index;
            final boolean isPreDiscontinuity = index < discontinuityIndex;

            // if set, intentionally introduce a discontinuity
            if (discontinue && index == discontinuityIndex) {
                originCurrentValue = originCurrentValue + getIntFromRange(MAX_DELTA_RANGE);
            }

            final PcesFile file = PcesFile.of(
                    ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, originCurrentValue, fileDirectory);

            // if set, apply custom logic to how (and if) bounds are advanced. Otherwise, advance bounds normally.
            if (shouldAdvanceBoundsPredicate == null || shouldAdvanceBoundsPredicate.test(index)) {
                lowerBound = rng.nextLong(lowerBound + 1, upperBound + 1);
                upperBound = upperBound + rng.nextInt(1, maxDelta);
            }
            timestamp = timestamp.plusMillis(getIntFromRange(TIMESTAP_RANGE));

            // if set, intentionally don't write a file
            if (skipElementAtHalf && index == halfIndex) {
                continue;
            }

            // it might be set to not generate some files at the beginning
            if (!skipAtStart || index >= startIndexIfSkip) {
                files.add(file);
                createDummyPcesFile(file);
                if (discontinue) {
                    if (isPreDiscontinuity) {
                        filesBeforeDiscontinuity.add(file);
                    } else {
                        filesAfterDiscontinuity.add(file);
                    }
                }
            }
        }

        long afterUnbrokenOrigin = discontinue ? rng.nextLong(originCurrentValue + 1, originCurrentValue + 1000) : -1;
        long beforeUnbrokenOrigin = discontinue ? rng.nextLong(originStartValue, originCurrentValue) : -1;
        return new PcesFilesGeneratorResult(
                filesBeforeDiscontinuity,
                filesAfterDiscontinuity,
                files,
                originStartValue,
                originCurrentValue,
                nonExistentValue,
                afterUnbrokenOrigin,
                beforeUnbrokenOrigin);
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
     * @param filesAfterDiscontinuity The list of files generated after the discontinuity.
     * @param files The list of all generated files.
     * @param startUnbrokenOrigin The starting unbrokenOrigin value.
     * @param resultingUnbrokenOrigin The final unbrokenOrigin value.
     * @param nonExistentValue A value that does not exist in any generated file.
     * @param pointAfterUnbrokenOrigin a random value placed after {@code resultingUnbrokenOrigin}
     * @param pointBeforeUnbrokenOrigin a random value placed before {@code resultingUnbrokenOrigin} and after {@code startUnbrokenOrigin}
     */
    public record PcesFilesGeneratorResult(
            @NonNull List<PcesFile> filesBeforeDiscontinuity,
            @NonNull List<PcesFile> filesAfterDiscontinuity,
            @NonNull List<PcesFile> files,
            long startUnbrokenOrigin,
            long resultingUnbrokenOrigin,
            long nonExistentValue,
            long pointAfterUnbrokenOrigin,
            long pointBeforeUnbrokenOrigin) {}

    /**
     * A builder for creating PcesTestFilesGenerator instances.
     */
    public static class Builder {
        private final AncientMode ancientMode;
        private final Random rng;
        private final Path fileDirectory;

        private Range originRange = null;
        private boolean ignoreSome;
        private boolean skipElementAtHalf;
        private boolean discontinue;
        private Predicate<Integer> shouldAdvanceBoundsPredicate;
        private Integer numFilesToGenerate = DEFAULT_NUM_FILES_TO_GENERATE;

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
        public static Builder create(
                final @NonNull AncientMode ancientMode, final @NonNull Random rng, final @NonNull Path fileDirectory) {
            return new Builder(ancientMode, rng, fileDirectory);
        }

        /**
         * Sets the generator to introduce a discontinuity in the resultingUnbrokenOrigin value.
         *
         * @return This Builder instance.
         */
        @NonNull
        public Builder discontinue() {
            discontinue = true;
            return this;
        }

        /**
         * Sets the generator to skip creating a file at the halfway point.
         *
         * @return This Builder instance.
         */
        @NonNull
        public Builder introduceGapHalfway() {
            skipElementAtHalf = true;
            return this;
        }

        /**
         * Sets the generator to skip creating some files at the start.
         *
         * @return This Builder instance.
         */
        @NonNull
        public Builder skipAtStart() {
            ignoreSome = true;
            return this;
        }

        /**
         * Sets the generator to use a range for origin value.
         *
         * @return This Builder instance.
         */
        @NonNull
        public Builder withOriginRange(final @NonNull Range range) {
            this.originRange = range;
            return this;
        }

        /**
         * Sets the generator to use a predicate that tells when to advance the bounds for the generated files.
         *
         * @return This Builder instance.
         */
        @NonNull
        public Builder withAdvanceBoundsStrategy(final @NonNull Predicate<Integer> shouldAdvanceBoundsPredicate) {
            this.shouldAdvanceBoundsPredicate = shouldAdvanceBoundsPredicate;
            return this;
        }
        /**
         * Sets the number of files to generate
         *
         * @return This Builder instance.
         */
        @NonNull
        public Builder withNumFilesToGenerate(final @NonNull Integer numFilesToGenerate) {
            if (numFilesToGenerate <= 0)
                throw new IllegalArgumentException("numFilesToGenerate should be higher than 0");
            this.numFilesToGenerate = numFilesToGenerate;
            return this;
        }

        /**
         * Builds a new PcesTestFilesGenerator instance.
         *
         * @return A new PcesTestFilesGenerator instance.
         */
        @NonNull
        public PcesTestFilesGenerator build() {
            return new PcesTestFilesGenerator(
                    originRange,
                    ancientMode,
                    rng,
                    numFilesToGenerate,
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
    public record Range(int start, int end) {}
}
