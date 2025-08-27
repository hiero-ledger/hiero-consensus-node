// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams;

import static com.swirlds.common.merkle.copy.MerkleInitialize.initializeAndMigrateTreeAfterDeserialization;
import static org.hiero.base.constructable.ClassIdFormatter.classIdString;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_CLASS_ID;

import com.swirlds.common.io.ExternalSelfSerializable;
import com.swirlds.common.io.exceptions.MerkleSerializationException;
import com.swirlds.common.io.streams.internal.MerkleSerializationProtocol;
import com.swirlds.common.io.streams.internal.MerkleTreeSerializationOptions;
import com.swirlds.common.io.streams.internal.PartiallyConstructedMerkleInternal;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.IllegalChildCountException;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.io.exceptions.ClassNotFoundException;
import org.hiero.base.io.streams.SerializableDataInputStream;

/**
 * A SerializableDataInputStream that can also handle merkle tree.
 */
public class MerkleDataInputStream extends SerializableDataInputStream {

    private final Queue<PartiallyConstructedMerkleInternal> internalNodes;
    private MerkleNode root;

    /**
     * Create a stream capable of reading merkle trees.
     *
     * @param in
     * 		a stream to wrap
     */
    public MerkleDataInputStream(final InputStream in) {
        super(in);
        internalNodes = new LinkedList<>();
    }

    /**
     * Add a child to its parent.
     *
     * @param child
     * 		the child to be added
     */
    private void addToParent(final MerkleNode child) {

        if (internalNodes.isEmpty()) {
            root = child;
        } else {
            PartiallyConstructedMerkleInternal nextParent = internalNodes.peek();
            nextParent.addChild(child);
            if (nextParent.hasAllChildren()) {
                nextParent.finishConstruction();
                internalNodes.remove();
            }
        }
    }

    /**
     * Finish deserializing a leaf node.
     *
     * @param directory
     * 		the directory from which data is being read
     * @param node
     * 		the leaf node to be read
     * @param version
     * 		version of this leaf
     */
    private void finishReadingLeaf(final Path directory, final MerkleLeaf node, final int version) throws IOException {
        ((ExternalSelfSerializable) node).deserialize(this, directory, version);
        addToParent(node);
    }

    /**
     * Finish deserializing an internal node.
     *
     * @param directory
     * 		the directory from which data is being read
     * @param node
     * 		the internal node to be read
     * @param version
     * 		version of this internal node
     */
    private void finishReadingInternal(final Path directory, final MerkleInternal node, final int version)
            throws IOException {

        if (node instanceof ExternalSelfSerializable) {
            ((ExternalSelfSerializable) node).deserialize(this, directory, version);
            addToParent(node);
        } else {
            final int childCount = readInt();

            if (childCount < node.getMinimumChildCount() || childCount > node.getMaximumChildCount()) {
                throw new IllegalChildCountException(
                        node.getClassId(),
                        version,
                        node.getMinimumChildCount(),
                        node.getMaximumChildCount(),
                        childCount);
            }

            addToParent(node);
            if (childCount > 0) {
                internalNodes.add(new PartiallyConstructedMerkleInternal(node, version, childCount));
            }
        }
    }

    /**
     * Read the node from the stream.
     *
     * @param directory
     * 		the directory from which data is being read
     * @param deserializedVersions
     * 		versions of deserialized nodes
     */
    protected void readNextNode(
            final Path directory, final Map<Long /* class ID */, Integer /* version */> deserializedVersions)
            throws IOException {
        final long classId = readLong();
        recordClassId(classId);
        if (classId == NULL_CLASS_ID) {
            addToParent(null);
            return;
        }

        final MerkleNode node = ConstructableRegistry.getInstance().createObject(classId);
        if (node == null) {
            throw new ClassNotFoundException(classId);
        }
        recordClass(node);

        final int classVersion = readInt();

        validateVersion(node, classVersion);
        final Integer previous = deserializedVersions.put(classId, classVersion);
        if (previous != null && previous != classVersion) {
            throw new IllegalStateException(
                    "Class with class ID " + classIdString(classId) + " has different versions within the same stream");
        }

        if (node.isLeaf()) {
            finishReadingLeaf(directory, node.asLeaf(), classVersion);
        } else {
            finishReadingInternal(directory, node.asInternal(), classVersion);
        }
    }

    /**
     * Perform basic sanity checks on the output directory.
     */
    @SuppressWarnings("DuplicatedCode")
    private static void validateDirectory(@NonNull final Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        if (!Files.exists(directory)) {
            throw new IllegalArgumentException("directory " + directory + " does not exist");
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("'directory' " + directory + " is not a directory");
        }
        if (!Files.isReadable(directory)) {
            throw new IllegalArgumentException("invalid read permissions for directory " + directory);
        }
    }

    /**
     * Read a merkle tree from a stream.
     *
     * @param configuration
     *      the configuration for this node
     * @param directory
     * 		the directory from which data is being read
     * @param maxNumberOfNodes
     * 		maximum number of nodes to read
     * @param <T>
     * 		Type of the node
     * @return the merkle tree read from the stream
     * @throws IOException
     * 		thrown when version or the options or nodes count are invalid
     */
    public <T extends MerkleNode> T readMerkleTree(
            @NonNull final Configuration configuration, final Path directory, final int maxNumberOfNodes)
            throws IOException {

        validateDirectory(directory);

        final int merkleVersion = readInt();

        if (merkleVersion == MerkleSerializationProtocol.VERSION_1_ORIGINAL) {
            throw new MerkleSerializationException("Unhandled merkle serialization version " + merkleVersion);
        } else if (merkleVersion == MerkleSerializationProtocol.VERSION_2_ADDED_OPTIONS) {
            readSerializable(false, MerkleTreeSerializationOptions::new);
        }

        final boolean rootIsNull = readBoolean();
        if (rootIsNull) {
            return null;
        }

        final Map<Long /* class ID */, Integer /* version */> deserializedVersions = new HashMap<>();

        int nodeCount = 0;
        while (!internalNodes.isEmpty() || root == null) {
            nodeCount++;
            if (nodeCount > maxNumberOfNodes) {
                throw new MerkleSerializationException("Node count exceeds maximum value of " + maxNumberOfNodes + ".");
            }
            readNextNode(directory, deserializedVersions);
        }

        final MerkleNode migratedRoot =
                initializeAndMigrateTreeAfterDeserialization(configuration, root, deserializedVersions);

        if (migratedRoot == null) {
            return null;
        }
        return migratedRoot.cast();
    }
}
