// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import static java.util.Objects.requireNonNull;

import com.swirlds.platform.ConsensusImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.hiero.consensus.model.notification.IssNotification.IssType;

/**
 * A data structure that holds the status of marker files for a node.
 */
public class MarkerFilesStatus {

    private volatile boolean hasCoinRoundMarkerFile;
    private volatile boolean hasMissingSuperMajorityMarkerFile;
    private volatile boolean hasMissingJudgesMarkerFile;
    private volatile boolean hasConsensusExceptionMarkerFile;
    private final Set<IssType> issMarkerFiles = Collections.synchronizedSet(EnumSet.noneOf(IssType.class));

    /**
     * Checks if the node wrote any marker file.
     *
     * @return {@code true} if the node wrote any marker file, {@code false} otherwise
     */
    public boolean hasAnyMarkerFile() {
        return hasCoinRoundMarkerFile()
                || hasMissingSuperMajorityMarkerFile()
                || hasMissingJudgesMarkerFile()
                || hasConsensusExceptionMarkerFile()
                || hasAnyIssMarkerFile();
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
     * Checks if the node wrote a missing-super-majority marker file.
     *
     * @return {@code true} if the node wrote a missing-super-majority marker file, {@code false} otherwise
     */
    public boolean hasMissingSuperMajorityMarkerFile() {
        return hasMissingSuperMajorityMarkerFile;
    }

    /**
     * Checks if the node wrote a missing-judges marker file.
     *
     * @return {@code true} if the node wrote a missing-judges marker file, {@code false} otherwise
     */
    public boolean hasMissingJudgesMarkerFile() {
        return hasMissingJudgesMarkerFile;
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
    public boolean hasAnyIssMarkerFile() {
        return Stream.of(IssType.values()).anyMatch(this::hasIssMarkerFileOfType);
    }

    /**
     * Checks if the node wrote an ISS marker file of a specific type.
     *
     * @param issType the type of ISS marker file to check
     * @return {@code true} if the node has an ISS marker file of the specified type, {@code false} otherwise
     * @throws NullPointerException if {@code issType} is {@code null}
     */
    public boolean hasIssMarkerFileOfType(@NonNull final IssType issType) {
        requireNonNull(issType);
        return issMarkerFiles.contains(issType);
    }

    /**
     * Updates the marker files status based on a list of marker file names.
     * *
     * <p>We only set flags to true and we only add elements to the set. Thus, we can treat the properties
     * independently and do not need to worry about race conditions.
     *
     * @param markerFileNames the list of marker file names to update the status with
     */
    public void updateWithMarkerFiles(final List<String> markerFileNames) {
        if (markerFileNames.contains(ConsensusImpl.COIN_ROUND_MARKER_FILE)) {
            this.hasCoinRoundMarkerFile = true;
        }
        if (markerFileNames.contains(ConsensusImpl.NO_SUPER_MAJORITY_MARKER_FILE)) {
            this.hasMissingSuperMajorityMarkerFile = true;
        }
        if (markerFileNames.contains(ConsensusImpl.NO_JUDGES_MARKER_FILE)) {
            this.hasMissingJudgesMarkerFile = true;
        }
        if (markerFileNames.contains(ConsensusImpl.CONSENSUS_EXCEPTION_MARKER_FILE)) {
            this.hasConsensusExceptionMarkerFile = true;
        }
        if (markerFileNames.contains(IssType.CATASTROPHIC_ISS.toString())) {
            this.issMarkerFiles.add(IssType.CATASTROPHIC_ISS);
        }
        if (markerFileNames.contains(IssType.OTHER_ISS.toString())) {
            this.issMarkerFiles.add(IssType.OTHER_ISS);
        }
        if (markerFileNames.contains(IssType.SELF_ISS.toString())) {
            this.issMarkerFiles.add(IssType.SELF_ISS);
        }
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", MarkerFilesStatus.class.getSimpleName() + "[", "]")
                .add("hasCoinRoundMarkerFile=" + hasCoinRoundMarkerFile)
                .add("hasMissingSuperMajorityMarkerFile=" + hasMissingSuperMajorityMarkerFile)
                .add("hasMissingJudgesMarkerFile=" + hasMissingJudgesMarkerFile)
                .add("hasConsensusExceptionMarkerFile=" + hasConsensusExceptionMarkerFile)
                .add("issMarkerFiles=" + issMarkerFiles)
                .toString();
    }
}
