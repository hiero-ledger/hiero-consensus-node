// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks that a state snapshot written to disk actually contains its MerkleDb data.
 *
 * <p>{@code SignedStateFileWriter.writeSignedStateFilesToDirectory} first writes the supplemental files
 * ({@code stateMetadata.txt}, {@code consensusSnapshot.json}, ...) and then the state itself via
 * {@code VirtualMapStateLifecycleManager.createSnapshot}. The latter catches every error and only logs
 * "Unable to write a snapshot on demand", so a failed snapshot (e.g. "Too many open files") leaves a directory that
 * looks complete but has no {@code data/<label>} and cannot be loaded. Callers use this check to fail at the point
 * of writing instead of much later, when the snapshot is first read.
 */
public final class SnapshotChecks {

    private SnapshotChecks() {}

    /**
     * Verifies that {@code snapshotDir} contains the MerkleDb data directory for the virtual map {@code label}
     * ({@code <snapshotDir>/data/<label>}, the layout {@code MerkleDbDataSourceBuilder} writes and restores).
     *
     * @param snapshotDir the directory the state snapshot was written to
     * @param label the label of the state's virtual map
     * @throws IllegalStateException if the data directory is missing or empty
     */
    public static void requireStateData(@NonNull final Path snapshotDir, @NonNull final String label) {
        requireNonNull(snapshotDir);
        requireNonNull(label);
        final Path dataDir = snapshotDir.resolve("data").resolve(label);
        boolean present;
        try (var entries = Files.list(dataDir)) {
            present = entries.findAny().isPresent();
        } catch (final Exception e) {
            present = false;
        }
        if (!present) {
            throw new IllegalStateException("State snapshot in " + snapshotDir + " is incomplete: " + dataDir
                    + " is missing or empty, so the state data was not written. The snapshot error is only logged — "
                    + "search the validator log for \"Unable to write a snapshot on demand\" for the cause "
                    + "(e.g. \"Too many open files\": raise the limit with `ulimit -n`).");
        }
    }
}
