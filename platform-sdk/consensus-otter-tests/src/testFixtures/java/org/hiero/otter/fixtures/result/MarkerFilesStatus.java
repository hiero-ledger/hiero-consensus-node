package org.hiero.otter.fixtures.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.stream.Stream;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.otter.fixtures.internal.result.MarkerFilesStatusImpl;

/**
 * A data structure that holds the status of marker files for a node.
 */
public class MarkerFilesStatus {

    private final boolean hasCoinRoundMarkerFile;
    private final boolean hasNoSuperMajorityMarkerFile;
    private final boolean hasNoJudgesMarkerFile;
    private final boolean hasConsensusExceptionMarkerFile;
    private final EnumSet<IssType> issMarkerFiles;

    public static final MarkerFilesStatus INITIAL_STATUS = new MarkerFilesStatusImpl(
            false, false, false, false, EnumSet.noneOf(IssType.class));

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
        return hasCoinRoundMarkerFile() || hasNoSuperMajorityMarkerFile() || hasNoJudgesMarkerFile()
                || hasConsensusExceptionMarkerFile() || hasAnyISSMarkerFile();
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
     * Returns a new instance of {@link MarkerFilesStatus} with the coin round marker file status set to {@code true}.
     *
     * @return a new {@link MarkerFilesStatus} with the updated status
     */
    @NonNull
    public MarkerFilesStatus withCoinRoundMarkerFile() {
        return new MarkerFilesStatus(true, hasNoSuperMajorityMarkerFile, hasNoJudgesMarkerFile,
                hasConsensusExceptionMarkerFile, issMarkerFiles);
    }

    /**
     * Returns a new instance of {@link MarkerFilesStatus} with the no-super-majority marker file status set to {@code true}.
     *
     * @return a new {@link MarkerFilesStatus} with the updated status
     */
    @NonNull
    public MarkerFilesStatus withNoSuperMajorityMarkerFile() {
        return new MarkerFilesStatus(hasCoinRoundMarkerFile, true, hasNoJudgesMarkerFile,
                hasConsensusExceptionMarkerFile, issMarkerFiles);
    }

    /**
     * Returns a new instance of {@link MarkerFilesStatus} with the no-judges marker file status set to {@code true}.
     *
     * @return a new {@link MarkerFilesStatus} with the updated status
     */
    @NonNull
    public MarkerFilesStatus withNoJudgesMarkerFile() {
        return new MarkerFilesStatus(hasCoinRoundMarkerFile, hasNoSuperMajorityMarkerFile, true,
                hasConsensusExceptionMarkerFile, issMarkerFiles);
    }

    /**
     * Returns a new instance of {@link MarkerFilesStatus} with the consensus exception marker file status set to {@code true}.
     *
     * @return a new {@link MarkerFilesStatus} with the updated status
     */
    @NonNull
    public MarkerFilesStatus withConsensusExceptionMarkerFile() {
        return new MarkerFilesStatus(hasCoinRoundMarkerFile, hasNoSuperMajorityMarkerFile, hasNoJudgesMarkerFile,
                true, issMarkerFiles);
    }

    /**
     * Returns a new instance of {@link MarkerFilesStatus} with the specified ISS marker file type added.
     *
     * @param issType the type of ISS marker file to add
     * @return a new {@link MarkerFilesStatus} with the updated status
     * @throws NullPointerException if {@code issType} is {@code null}
     */
    @NonNull
    public MarkerFilesStatus withISSMarkerFile(@NonNull final IssType issType) {
        final EnumSet<IssType> newIssMarkerFiles = EnumSet.copyOf(issMarkerFiles);
        newIssMarkerFiles.add(issType);
        return new MarkerFilesStatus(hasCoinRoundMarkerFile, hasNoSuperMajorityMarkerFile,
                hasNoJudgesMarkerFile, hasConsensusExceptionMarkerFile, newIssMarkerFiles);
    }
}
