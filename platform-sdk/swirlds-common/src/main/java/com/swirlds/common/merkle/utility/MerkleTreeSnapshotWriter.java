// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import static com.swirlds.common.io.utility.FileUtils.writeAndFlush;
import static com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader.SIGNED_STATE_FILE_NAME;
import static com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader.SIG_SET_SEPARATE_STATE_FILE_VERSION;
import static com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader.VERSIONED_FILE_BYTE;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;

import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for writing a snapshot of a Merkle tree to disk.
 */
public final class MerkleTreeSnapshotWriter {

    private static final Logger logger = LogManager.getLogger(MerkleTreeSnapshotWriter.class);

    private MerkleTreeSnapshotWriter() {
        // prevent instantiation
    }

    /**
     * Writes a snapshot of the given {@link MerkleNode} to the given {@link Path}.
     * @param merkleRoot the {@link MerkleNode} representing a root of the Merkle tree to write
     * @param targetPath the {@link Path} to write the snapshot to
     */
    public static void createSnapshot(
            @NonNull final MerkleNode merkleRoot, @NonNull final Path targetPath, long round) {
        logger.info(STATE_TO_DISK.getMarker(), "Creating a snapshot on demand in {} for round {}", targetPath, round);
        try {
            writeMerkleRootToFile(targetPath, merkleRoot);
            logger.info(
                    STATE_TO_DISK.getMarker(),
                    "Successfully created a snapshot on demand in {}  for round {}",
                    targetPath,
                    round);
        } catch (final Throwable e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Unable to write a snapshot on demand for round {} to {}.",
                    round,
                    targetPath,
                    e);
        }
    }

    private static void writeMerkleRootToFile(@NonNull final Path directory, @NonNull final MerkleNode merkleRoot)
            throws IOException {
        writeAndFlush(
                directory.resolve(SIGNED_STATE_FILE_NAME), out -> writeMerkleRootToStream(out, directory, merkleRoot));
    }

    private static void writeMerkleRootToStream(
            @NonNull final MerkleDataOutputStream out,
            @NonNull final Path directory,
            @NonNull final MerkleNode merkleRoot)
            throws IOException {
        out.write(VERSIONED_FILE_BYTE);
        out.writeInt(SIG_SET_SEPARATE_STATE_FILE_VERSION);
        out.writeProtocolVersion();
        out.writeMerkleTree(directory, merkleRoot);
        out.writeSerializable(merkleRoot.getHash(), true);
    }
}
