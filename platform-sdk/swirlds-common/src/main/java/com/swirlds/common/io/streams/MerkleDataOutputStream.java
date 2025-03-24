// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams;

import static com.swirlds.common.merkle.iterators.MerkleIterationOrder.BREADTH_FIRST;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;

import com.swirlds.common.io.ExternalSelfSerializable;
import com.swirlds.common.io.streams.internal.MerkleSerializationProtocol;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A SerializableDataOutputStream that also handles merkle trees.
 */
public class MerkleDataOutputStream extends SerializableDataOutputStreamImpl {

    private static final Logger logger = LogManager.getLogger(MerkleDataOutputStream.class);

    private static final Predicate<MerkleInternal> DESCENDANT_FILTER =
            node -> !(node instanceof ExternalSelfSerializable);

    /**
     * Create a new merkle stream.
     *
     * @param out
     * 		the output stream
     */
    public MerkleDataOutputStream(final OutputStream out) {
        super(out);
    }

    /**
     * Write a node that implements the type {@link ExternalSelfSerializable}.
     */
    private void writeSerializableNode(final Path directory, final ExternalSelfSerializable node) throws IOException {

        writeClassIdVersion(node, true);
        node.serialize(this, directory);
    }

    /**
     * Default serialization algorithm for internal nodes that do not implement their own serialization.
     */
    private void writeDefaultInternalNode(final MerkleInternal node) throws IOException {
        writeLong(node.getClassId());
        writeInt(node.getVersion());
        writeInt(node.getNumberOfChildren());
    }

    /**
     * Writes a MerkleInternal node to the stream.
     */
    private void writeInternal(final Path directory, final MerkleInternal node) throws IOException {
        if (node instanceof ExternalSelfSerializable externalSelfSerializable) {
            writeSerializableNode(directory, externalSelfSerializable);
        } else {
            writeDefaultInternalNode(node);
        }
    }

    /**
     * Write a leaf node to the stream.
     */
    private void writeLeaf(final Path directory, final MerkleLeaf node) throws IOException {
        writeSerializableNode(directory, node);
    }

    /**
     * Write a null leaf to the stream.
     */
    private void writeNull() throws IOException {
        writeSerializable(null, true);
    }

    /**
     * Perform basic sanity checks on the output directory.
     */
    @SuppressWarnings({"DuplicatedCode", "resource"})
    private static void validateDirectory(final Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        if (!Files.exists(directory)) {
            throw new IllegalArgumentException("directory " + directory + " does not exist");
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("'directory' " + directory + " is not a directory");
        }
        if (!Files.isReadable(directory)) {
            throw new IllegalArgumentException("invalid read permissions for directory " + directory);
        }
        if (!Files.isWritable(directory)) {
            throw new IllegalArgumentException("invalid write permissions for directory " + directory);
        }

        try (final Stream<Path> list = Files.list(directory)) {
            final List<Path> contents = list.toList();
            if (contents.size() > 1) {
                // At this point in time, the only thing in this directory should be SignedState.swh.
                // If there are other files, then something funny may be going on.
                final StringBuilder sb = new StringBuilder();
                sb.append("merkle tree being written to directory ")
                        .append(directory)
                        .append(" that already contains data. Contents:");
                for (final Path path : contents) {
                    sb.append("\n   ").append(path);
                }

                logger.info(STATE_TO_DISK.getMarker(), sb);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes a merkle tree to a stream.
     *
     * @param directory
     * 		a directory where additional data will be written
     * @param root
     * 		the root of the tree
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public void writeMerkleTree(final Path directory, final MerkleNode root) throws IOException {
        writeInt(MerkleSerializationProtocol.CURRENT);
        writeBoolean(root == null);

        validateDirectory(directory);

        if (root == null) {
            return;
        }

        root.treeIterator()
                .setOrder(BREADTH_FIRST)
                .setDescendantFilter(DESCENDANT_FILTER)
                .ignoreNull(false)
                .forEachRemainingWithIO((final MerkleNode node) -> {
                    if (node == null) {
                        writeNull();
                    } else if (node.isLeaf()) {
                        writeLeaf(directory, node.asLeaf());
                    } else {
                        writeInternal(directory, node.asInternal());
                    }
                });
    }
}
