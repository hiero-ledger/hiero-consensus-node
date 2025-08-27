// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import static com.swirlds.common.io.streams.StreamDebugUtils.deserializeAndDebugOnFailure;

import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.hiero.base.crypto.Hash;

/**
 * Utility class for reading a snapshot of a Merkle tree from disk.
 */
public class MerkleTreeSnapshotReader {

    /**
     * The current version of the signed state file. A file of this version no longer contains the signature set,
     * instead the signature set is stored in a separate file.
     */
    public static final int SIG_SET_SEPARATE_STATE_FILE_VERSION = 2;
    /**
     * The supported versions of the signed state file
     */
    public static final Set<Integer> SUPPORTED_STATE_FILE_VERSIONS = Set.of(SIG_SET_SEPARATE_STATE_FILE_VERSION);
    /**
     * Prior to v1, the signed state file was not versioned. This byte was introduced in v1 to mark a versioned file.
     */
    public static final byte VERSIONED_FILE_BYTE = Byte.MAX_VALUE;
    /**
     * Fun trivia: the file extension ".swh" stands for "SWirlds Hashgraph", although this is a bit misleading... as
     * this file nor actually contains a hashgraph neither a set of signatures.
     */
    public static final String SIGNED_STATE_FILE_NAME = "SignedState.swh";

    public static final int MAX_MERKLE_NODES_IN_STATE = Integer.MAX_VALUE;

    /**
     * This is a helper class to hold the data read from a state file.
     * @param stateRoot the root of Merkle tree state
     * @param hash the hash of the state
     */
    public record StateFileData(@NonNull MerkleNode stateRoot, @NonNull Hash hash) {}

    /**
     * Reads a state file from disk
     *
     * @param configuration the configuration for this node
     * @param stateFile the file to read from
     * @return a signed state with it's associated hash (as computed when the state was serialized)
     * @throws IOException if there is any problems with reading from a file
     */
    @NonNull
    public static StateFileData readStateFileData(
            @NonNull final Configuration configuration, @NonNull final Path stateFile) throws IOException {
        return deserializeAndDebugOnFailure(
                () -> new BufferedInputStream(new FileInputStream(stateFile.toFile())),
                (final MerkleDataInputStream in) -> {
                    final int fileVersion = readAndCheckStateFileVersion(in);

                    final Path directory = stateFile.getParent();
                    if (fileVersion == SIG_SET_SEPARATE_STATE_FILE_VERSION) {
                        return readStateFileData(configuration, stateFile, in, directory);
                    } else {
                        throw new IOException("Unsupported state file version: " + fileVersion);
                    }
                });
    }

    /**
     * This method reads the state file data from state file.
     */
    @NonNull
    private static StateFileData readStateFileData(
            @NonNull final Configuration configuration,
            @NonNull final Path stateFile,
            @NonNull final MerkleDataInputStream in,
            @NonNull final Path directory)
            throws IOException {
        try {
            final MerkleNode state = in.readMerkleTree(configuration, directory, MAX_MERKLE_NODES_IN_STATE);
            final Hash hash = in.readSerializable();
            return new StateFileData(state, hash);

        } catch (final IOException e) {
            throw new IOException("Failed to read snapshot file " + stateFile.toFile(), e);
        }
    }

    /**
     * Read the version from a signed state file and check it
     *
     * @param in the stream to read from
     * @throws IOException if the version is invalid
     * @return the protocol version
     */
    private static int readAndCheckStateFileVersion(@NonNull final MerkleDataInputStream in) throws IOException {
        final byte versionByte = in.readByte();
        if (versionByte != VERSIONED_FILE_BYTE) {
            throw new IOException("File is not versioned -- data corrupted or is an unsupported legacy state");
        }

        final int fileVersion = in.readInt();
        if (!SUPPORTED_STATE_FILE_VERSIONS.contains(fileVersion)) {
            throw new IOException("Unsupported file version: " + fileVersion);
        }
        in.readProtocolVersion();
        return fileVersion;
    }
}
