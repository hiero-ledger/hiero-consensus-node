// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import static java.util.Objects.requireNonNull;

import com.swirlds.platform.ConsensusImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.hiero.consensus.model.notification.IssNotification.IssType;

/**
 * A data structure that holds the status of marker files for a node.
 */
public class MarkerFilesStatus {

    private final boolean hasCoinRoundMarkerFile;
    private final boolean hasNoSuperMajorityMarkerFile;
    private final boolean hasNoJudgesMarkerFile;
    private final boolean hasConsensusExceptionMarkerFile;
    private final EnumSet<IssType> issMarkerFiles;

    public static final MarkerFilesStatus INITIAL_STATUS =
            new MarkerFilesStatus(false, false, false, false, EnumSet.noneOf(IssType.class));

    /**
     * Creates a new instance of {@link MarkerFilesStatus}.
     *
     * @param hasCoinRoundMarkerFile indicates if the node wrote a coin round marker file
     * @param hasNoSuperMajorityMarkerFile indicates if the node wrote a no-super-majority marker file
     * @param hasNoJudgesMarkerFile indicates if the node wrote a no-judges marker file
     * @param hasConsensusExceptionMarkerFile indicates if the node has a consensus exception marker file
     * @param issMarkerFiles the set of ISS marker files written by the node
     */
    public MarkerFilesStatus(
            final boolean hasCoinRoundMarkerFile,
            final boolean hasNoSuperMajorityMarkerFile,
            final boolean hasNoJudgesMarkerFile,
            final boolean hasConsensusExceptionMarkerFile,
            @NonNull final EnumSet<IssType> issMarkerFiles) {
        this.hasCoinRoundMarkerFile = hasCoinRoundMarkerFile;
        this.hasNoSuperMajorityMarkerFile = hasNoSuperMajorityMarkerFile;
        this.hasNoJudgesMarkerFile = hasNoJudgesMarkerFile;
        this.hasConsensusExceptionMarkerFile = hasConsensusExceptionMarkerFile;
        this.issMarkerFiles = EnumSet.copyOf(issMarkerFiles);
    }

    /**
     * Checks if the node wrote any marker file.
     *
     * @return {@code true} if the node wrote any marker file, {@code false} otherwise
     */
    public boolean hasAnyMarkerFile() {
        return hasCoinRoundMarkerFile()
                || hasNoSuperMajorityMarkerFile()
                || hasNoJudgesMarkerFile()
                || hasConsensusExceptionMarkerFile()
                || hasAnyISSMarkerFile();
    }

    /**
     * Checks if the node wrote a coin round marker file.
     *
     * @return {@code true} if the node wrote a coin round marker file, {@code false} otherwise
     */
    public boolean hasCoinRoundMarkerFile() {
        return hasCoinRoundMarkerFile;
    }

    /**
     * Checks if the node wrote a no-super-majority marker file.
     *
     * @return {@code true} if the node wrote a no-super-majority marker file, {@code false} otherwise
     */
    public boolean hasNoSuperMajorityMarkerFile() {
        return hasNoSuperMajorityMarkerFile;
    }

    /**
     * Checks if the node wrote a no-judges marker file.
     *
     * @return {@code true} if the node wrote a no-judges marker file, {@code false} otherwise
     */
    public boolean hasNoJudgesMarkerFile() {
        return hasNoJudgesMarkerFile;
    }

    /**
     * Checks if the node has a consensus exception marker file.
     *
     * @return {@code true} if the node has a consensus exception marker file, {@code false} otherwise
     */
    public boolean hasConsensusExceptionMarkerFile() {
        return hasConsensusExceptionMarkerFile;
    }

    /**
     * Checks if the node has any ISS marker file.
     *
     * @return {@code true} if the node has any ISS marker file, {@code false} otherwise
     */
    public boolean hasAnyISSMarkerFile() {
        return Stream.of(IssType.values()).anyMatch(this::hasISSMarkerFileOfType);
    }

    /**
     * Checks if the node wrote an ISS marker file of a specific type.
     *
     * @param issType the type of ISS marker file to check
     * @return {@code true} if the node has an ISS marker file of the specified type, {@code false} otherwise
     * @throws NullPointerException if {@code issType} is {@code null}
     */
    public boolean hasISSMarkerFileOfType(@NonNull final IssType issType) {
        requireNonNull(issType);
        return issMarkerFiles.contains(issType);
    }

    /**
     * Returns a new instance of {@link MarkerFilesStatus} with the specified marker files added to this instance.
     *
     * @param markerFileNames the list of marker file names to set
     * @return a new {@link MarkerFilesStatus} with the updated marker file names
     */
    public MarkerFilesStatus withMarkerFiles(final List<String> markerFileNames) {
        final EnumSet<IssType> newIssMarkerFiles = EnumSet.copyOf(issMarkerFiles);
        for (final String markerFileName : markerFileNames) {
            try {
                newIssMarkerFiles.add(IssType.valueOf(markerFileName));
            } catch (final IllegalArgumentException e) {
                // ignore if the marker file name is not a valid IssType
            }
        }
        return new MarkerFilesStatus(
                this.hasCoinRoundMarkerFile || markerFileNames.contains(ConsensusImpl.COIN_ROUND_MARKER_FILE),
                this.hasNoSuperMajorityMarkerFile
                        || markerFileNames.contains(ConsensusImpl.NO_SUPER_MAJORITY_MARKER_FILE),
                this.hasNoJudgesMarkerFile || markerFileNames.contains(ConsensusImpl.NO_JUDGES_MARKER_FILE),
                this.hasConsensusExceptionMarkerFile
                        || markerFileNames.contains(ConsensusImpl.CONSENSUS_EXCEPTION_MARKER_FILE),
                newIssMarkerFiles);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", MarkerFilesStatus.class.getSimpleName() + "[", "]")
                .add("hasCoinRoundMarkerFile=" + hasCoinRoundMarkerFile)
                .add("hasNoSuperMajorityMarkerFile=" + hasNoSuperMajorityMarkerFile)
                .add("hasNoJudgesMarkerFile=" + hasNoJudgesMarkerFile)
                .add("hasConsensusExceptionMarkerFile=" + hasConsensusExceptionMarkerFile)
                .add("issMarkerFiles=" + issMarkerFiles)
                .toString();
    }
}
