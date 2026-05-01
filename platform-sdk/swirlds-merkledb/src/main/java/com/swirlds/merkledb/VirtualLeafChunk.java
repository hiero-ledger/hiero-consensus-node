package com.swirlds.merkledb;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class VirtualLeafChunk {

    // Chunk ID
    public static final FieldDefinition FIELD_LEAFCHUNK_ID =
            new FieldDefinition("id", FieldType.FIXED64, false, true, false, 1);

    // Chunk size
    public static final FieldDefinition FIELD_LEAFCHUNK_SIZE =
            new FieldDefinition("len", FieldType.FIXED32, false, false, false, 2);

    // The path of the first non-null leaf
    public static final FieldDefinition FIELD_LEAFCHUNK_FIRSTPATH =
            new FieldDefinition("firstPath", FieldType.FIXED64, false, false, false, 3);

    // Leaf (VirtualLeafBytes), repeated
    public static final FieldDefinition FIELD_LEAFCHUNK_LEAF =
            new FieldDefinition("leaf", FieldType.MESSAGE, true, true, false, 4);

    // Chunk ID
    private final long id;

    // Max number of leaves in the chunk
    private final int size;

    // Leaves, as bytes or parsed objects
    private final AtomicReferenceArray<EitherBytesOrLeaf> leaves;

    public VirtualLeafChunk(final long id, final int size) {
        if (id < 0) {
            throw new IllegalArgumentException("Leaf chunk ID must be >= 0");
        }
        this.id = id;
        if (size <= 0) {
            throw new IllegalArgumentException("Leaf chunk size must be > 0");
        }
        this.size = size;
        this.leaves = new AtomicReferenceArray<>(size);
    }

    public VirtualLeafChunk copy() {
        final VirtualLeafChunk copy = new VirtualLeafChunk(id, size);
        for (int i = 0; i < size; i++) {
            copy.leaves.set(i, leaves.get(i));
        }
        return copy;
    }

    public long id() {
        return id;
    }

    public int size() {
        return size;
    }

    public long getFirstPath() {
        return getFirstPath(id, size);
    }

    public static long getFirstPath(final long chunkId, final int chunkSize) {
        return chunkId * chunkSize;
    }

    public long getLastPath() {
        return getLastPath(id, size);
    }

    public static long getLastPath(final long chunkId, final int chunkSize) {
        return (chunkId + 1) * chunkSize - 1;
    }

    public boolean containsPath(final long path) {
        assert path != Path.INVALID_PATH;
        return (path >= getFirstPath()) && (path <= getLastPath());
    }

    // Both paths inclusive
    public boolean inRange(final long firstPath, final long lastPath) {
        return inRange(id, size, firstPath, lastPath);
    }

    public static boolean inRange(final long chunkId, final int chunkSize, final long firstPath, final long lastPath) {
        return (getFirstPath(chunkId, chunkSize) <= lastPath) && (getLastPath(chunkId, chunkSize) >= firstPath);
    }

    public long pathToChunkId(final long path) {
        return pathToChunkId(path, size);
    }

    public static long pathToChunkId(final long path, final int chunkSize) {
        assert path != Path.INVALID_PATH;
        return path / chunkSize;
    }

    public static VirtualLeafChunk parseFrom(final ReadableSequentialData in, final int size) {
        if (in == null) {
            return null;
        }

        long id = 0;
        long firstNonNullLeafPath = 0;
        final List<EitherBytesOrLeaf> leaves = new ArrayList<>(size);

        while (in.hasRemaining()) {
            final int field = in.readVarInt(false);
            final int tag = field >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (tag == FIELD_LEAFCHUNK_ID.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                id = in.readLong();
            } else if (tag == FIELD_LEAFCHUNK_SIZE.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_32_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                final int s = in.readInt();
                if (size != s) {
                    throw new IllegalArgumentException("Leaf chunk size mismatch: exp=" + size + ", act=" + s);
                }
            } else if (tag == FIELD_LEAFCHUNK_FIRSTPATH.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                firstNonNullLeafPath = in.readLong();
            } else if (tag == FIELD_LEAFCHUNK_LEAF.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                final int leafLen = in.readVarInt(false);
                // This creates a "clean" leaf
                final byte[] leafBytes = new byte[leafLen];
                in.readBytes(leafBytes);
                leaves.add(new EitherBytesOrLeaf(leafBytes));
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        final long firstChunkPath = getFirstPath(id, size);
        final long lastChunkPath = getLastPath(id, size);
        if ((firstNonNullLeafPath < firstChunkPath) || (firstNonNullLeafPath > lastChunkPath)) {
            throw new IllegalArgumentException("First non-null leaf path is out of range");
        }
        if (leaves.isEmpty()) {
            throw new IllegalArgumentException("No leaves");
        }
//        if (leaves.size() != size - (firstNonNullLeafPath - firstChunkPath)) {
//            throw new IllegalArgumentException("Leaf count mismatch");
//        }

        final VirtualLeafChunk chunk = new VirtualLeafChunk(id, size);
        for (int i = 0; i < leaves.size(); i++) {
            final EitherBytesOrLeaf leaf = leaves.get(i);
            chunk.leaves.set((int) (firstNonNullLeafPath - firstChunkPath + i), leaf);
        }

        return chunk;
    }

    /// Parses a single leaf with a given path from the input.
    ///
    /// @throws IllegalArgumentException If the path is not in the leaf chunk
    /// @throws IllegalArgumentException If the parsed leaf chunk size is different from the given size
    public static VirtualLeafBytes<?> parseLeafFrom(final BufferedData in, final long path, final int size) {
        // Leaf offsets (even) and lengths (odd) in the input
        final int[] leafOffsetsAndLengths = new int[size * 2];
        long firstNonNullLeafPath = 0;
        int leafIndex = 0;
        while (in.hasRemaining()) {
            final int field = in.readVarInt(false);
            final int tag = field >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (tag == FIELD_LEAFCHUNK_ID.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                in.readLong(); // skip chunk ID
            } else if (tag == FIELD_LEAFCHUNK_SIZE.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_32_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                final int s = in.readInt();
                if (size != s) {
                    throw new IllegalArgumentException("Leaf chunk size mismatch: exp=" + size + ", act=" + s);
                }
            } else if (tag == FIELD_LEAFCHUNK_FIRSTPATH.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                firstNonNullLeafPath = in.readLong();
            } else if (tag == FIELD_LEAFCHUNK_LEAF.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                if (leafIndex >= size) {
                    throw new IllegalArgumentException("Too many leafs in a leaf chunk");
                }
                final int leafLen = in.readVarInt(false);
                leafOffsetsAndLengths[leafIndex * 2] = Math.toIntExact(in.position());
                leafOffsetsAndLengths[leafIndex * 2 + 1] = leafLen;
                leafIndex++;
                in.skip(leafLen);
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        final int resultIndex = Math.toIntExact(path -  firstNonNullLeafPath);
        if (resultIndex >= size) {
            throw new IllegalArgumentException("Requested leaf is not in the chunk, path=" + path + ", first=" + firstNonNullLeafPath);
        }
        final int offset = leafOffsetsAndLengths[resultIndex * 2];
        final int length = leafOffsetsAndLengths[resultIndex * 2 + 1];
        return VirtualLeafBytes.parseFrom(in.slice(offset, length));
    }

    public int getSizeInBytes() {
        int size = 0;
        if (id != 0) {
            size += ProtoWriterTools.sizeOfTag(FIELD_LEAFCHUNK_ID);
            size += Long.BYTES;
        }
        // size is always > 0
        size += ProtoWriterTools.sizeOfTag(FIELD_LEAFCHUNK_SIZE);
        size += Integer.BYTES;
        // First non-null leaf path is always > 0
        final long firstNonNullLeafPath = firstNonNullLeafPath();
        assert firstNonNullLeafPath > 0;
        size += ProtoWriterTools.sizeOfTag(FIELD_LEAFCHUNK_FIRSTPATH);
        size += Long.BYTES;
        // Leaves
        for (int i = 0; i < this.size; i++) {
            final EitherBytesOrLeaf leaf = leaves.get(i);
            if (leaf != null) {
                size += ProtoWriterTools.sizeOfDelimited(FIELD_LEAFCHUNK_LEAF, leaf.getSizeInBytes());
            }
        }
        return size;
    }

    public void writeTo(@NonNull final WritableSequentialData out) {
        final long pos = out.position();
        if (id != 0) {
            ProtoWriterTools.writeTag(out, FIELD_LEAFCHUNK_ID);
            out.writeLong(id);
        }
        // size is always > 0
        ProtoWriterTools.writeTag(out, FIELD_LEAFCHUNK_SIZE);
        out.writeInt(size);
        // First non-null leaf path is always > 0
        final long firstNonNullLeafPath = firstNonNullLeafPath();
        assert firstNonNullLeafPath > 0;
        ProtoWriterTools.writeTag(out, FIELD_LEAFCHUNK_FIRSTPATH);
        out.writeLong(firstNonNullLeafPath);
        // Leaves
        for (int i = 0; i < size; i++) {
            final EitherBytesOrLeaf leaf = leaves.get(i);
            if (leaf != null) {
                ProtoWriterTools.writeTag(out, FIELD_LEAFCHUNK_LEAF); // tag
                out.writeVarInt(leaf.getSizeInBytes(), false); // len
                leaf.writeTo(out); // bytes
            }
        }
        assert out.position() == pos + getSizeInBytes();
    }

    private long firstNonNullLeafPath() {
        for (int i = 0; i < size; i++) {
            final EitherBytesOrLeaf leaf = leaves.get(i);
            if (leaf != null) {
                return getFirstPath() + i;
            }
        }
        return Path.INVALID_PATH;
    }

    @SuppressWarnings("unchecked")
    public <V> VirtualLeafBytes<V> getLeaf(final long path) {
        final int index = getPathIndex(path);
        final EitherBytesOrLeaf e = leaves.get(index);
        final VirtualLeafBytes<V> leaf = e != null ? (VirtualLeafBytes<V>) e.leaf() : null;
        assert (leaf == null) || (leaf.path() == path);
        return leaf;
    }

    public void setLeaf(@NonNull final VirtualLeafBytes<?> leaf) {
        final long path = leaf.path();
        assert path > Path.ROOT_PATH;
        final int index = getPathIndex(path);
        leaves.set(index, new EitherBytesOrLeaf(leaf));
    }

    private int getPathIndex(final long path) {
        assert path != Path.INVALID_PATH;
        final long firstPath = getFirstPath();
        final long lastPath = getLastPath();
        if ((path < firstPath) || (path > lastPath)) {
            throw new IllegalArgumentException("Path " + path + " is not in the leaf chunk ID=" + id);
        }
        return (int) (path - firstPath);
    }

    private static class EitherBytesOrLeaf {

        // Null bytes indicate a dirty (updated) leaf
        private final byte[] bytes;

        private VirtualLeafBytes<?> leaf;

        EitherBytesOrLeaf(@NonNull final byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes);
            this.leaf = null;
        }

        EitherBytesOrLeaf(@NonNull final VirtualLeafBytes<?> leaf) {
            this.bytes = null;
            this.leaf = Objects.requireNonNull(leaf);
        }

        VirtualLeafBytes<?> leaf() {
            if (leaf != null) {
                return leaf;
            }
            assert bytes != null;
            leaf = VirtualLeafBytes.parseFromImmutableBytes(bytes);
            return leaf;
        }

        int getSizeInBytes() {
            if (bytes != null) {
                return Math.toIntExact(bytes.length);
            } else {
                assert leaf != null;
                return leaf.getSizeInBytes();
            }
        }

        void writeTo(@NonNull final WritableSequentialData out) {
            if (bytes != null) {
                out.writeBytes(bytes);
            } else {
                leaf.writeTo(out);
            }
        }
    }
}
