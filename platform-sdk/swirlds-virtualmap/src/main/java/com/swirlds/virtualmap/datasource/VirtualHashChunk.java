// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/**
 * A hash chunk is a set of hashes in a virtual sub-tree. Every chunk has 2 hashes at the top level,
 * 4 hashes at the second level, and so on.
 *
 * <p>The number of ranks in a chunk is called chunk height. Minimal chunk height is 1, such chunks
 * contain just two hashes.
 *
 * <p>A chunk is identified by the chunk path, which is a parent of its two top-most hashes. For
 * example, the root chunk is identified with path 0 (0 is a parent of 1 and 2). If chunk height is 2,
 * the root chunk at path 0 contains hashes 1, 2, 3, 4, 5, and 6. Such chunk has 4 child chunks
 * identified by paths 3, 4, 5, and 6. For example, chunk 4 has hashes 9, 10, 19, 20, 21, and 22.
 * Note that a hash at a chunk path does not belong to the chunk, but to its parent chunk, except
 * the root node hash, which doesn't belong to any chunk.
 *
 * <p>To store chunks, it doesn’t make sense to index them using chunk paths as it would result in
 * index size equal to virtual map size with lots of gaps. Instead, every chunk has an ID, the root
 * chunk has ID=0, the first root child chunk has ID=1, and so on. Chunk paths, IDs, and heights are
 * related. For example, when height is 2, chunk with ID=1 has path=3. When height is 3, chunk with
 * ID=1 has path=7.
 *
 * <p>The number of hashes in a chunk is 2^height, it is called chunk size. Since chunk index is based
 * on chunk IDs, index size is “chunk size” smaller than the size of the virtual map. Chunks closer to
 * the leaf rank may be incomplete, i.e. contain fewer hashes than the full size. The number of hashes
 * in {@link #hashData} is always the full chunk size.
 *
 * @param path Chunk path
 * @param height Chunk height
 * @param defaultHeight Default chunk height in the system. All chunks but those at a few last
 *                      ranks should have this height
 * @param hashData Chunk hashes
 */
public record VirtualHashChunk(long path, int height, int defaultHeight, @NonNull byte[] hashData) {

    public static final FieldDefinition FIELD_HASHCHUNK_PATH =
            new FieldDefinition("path", FieldType.FIXED64, false, true, false, 1);
    public static final FieldDefinition FIELD_HASHCHUNK_HEIGHT =
            new FieldDefinition("height", FieldType.FIXED32, false, true, false, 2);
    public static final FieldDefinition FIELD_HASHCHUNK_DEFAULTHEIGHT =
            new FieldDefinition("defaultHeight", FieldType.FIXED32, false, true, false, 3);
    public static final FieldDefinition FIELD_HASHCHUNK_HASHDATA =
            new FieldDefinition("hashData", FieldType.BYTES, false, false, false, 4);

    public VirtualHashChunk {
        if (height <= 0) {
            throw new IllegalArgumentException("Wrong chunk height: " + height);
        }
        if (defaultHeight <= 0) {
            throw new IllegalArgumentException("Wrong chunk default height: " + height);
        }
        final int rank = Path.getRank(path);
        if (rank % defaultHeight != 0) {
            throw new IllegalArgumentException("Wrong chunk rank/defaultHeight: " + rank + "/" + height);
        }
        if (hashData == null) {
            throw new IllegalArgumentException("Null hash data");
        }
        final int chunkSize = getChunkSize(height);
        final int dataLength = hashData.length;
        // Hash data length must always be hash length * chunk size, even if the number of hashes
        // is less than chunk size (partial chunks)
        if (dataLength != Cryptography.DEFAULT_DIGEST_TYPE.digestLength() * chunkSize) {
            throw new IllegalArgumentException("Wrong hash data length: " + dataLength);
        }
    }

    public VirtualHashChunk(final long path, final int defaultHeight) {
        this(
                path,
                defaultHeight,
                defaultHeight,
                new byte[getChunkSize(defaultHeight) * Cryptography.DEFAULT_DIGEST_TYPE.digestLength()]);
    }

    public VirtualHashChunk(final long path, final int height, final int defaultHeight) {
        this(
                path,
                height,
                defaultHeight,
                new byte[getChunkSize(height) * Cryptography.DEFAULT_DIGEST_TYPE.digestLength()]);
    }

    public VirtualHashChunk copy() {
        final byte[] dataCopy = new byte[hashData.length];
        System.arraycopy(hashData, 0, dataCopy, 0, hashData.length);
        return new VirtualHashChunk(path, height, defaultHeight, dataCopy);
    }

    public static VirtualHashChunk parseFrom(final ReadableSequentialData in) {
        if (in == null) {
            return null;
        }

        long path = 0;
        int height = 0;
        int defaultHeight = 0;
        byte[] hashData = null;

        while (in.hasRemaining()) {
            final int field = in.readVarInt(false);
            final int tag = field >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (tag == FIELD_HASHCHUNK_PATH.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                path = in.readLong();
            } else if (tag == FIELD_HASHCHUNK_HEIGHT.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_32_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                height = in.readInt();
            } else if (tag == FIELD_HASHCHUNK_DEFAULTHEIGHT.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_32_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                defaultHeight = in.readInt();
            } else if (tag == FIELD_HASHCHUNK_HASHDATA.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                final int len = in.readVarInt(false);
                hashData = new byte[len];
                if (in.readBytes(hashData) != len) {
                    throw new IllegalArgumentException("Failed to read " + len + " bytes");
                }
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        return new VirtualHashChunk(path, height, defaultHeight, hashData);
    }

    public int getSizeInBytes() {
        int size = 0;
        size += ProtoWriterTools.sizeOfTag(FIELD_HASHCHUNK_PATH);
        // Path is FIXED64
        size += Long.BYTES;
        // Height is always > 0
        size += ProtoWriterTools.sizeOfTag(FIELD_HASHCHUNK_HEIGHT);
        // Height is FIXED32
        size += Integer.BYTES;
        // Default height is always > 0
        size += ProtoWriterTools.sizeOfTag(FIELD_HASHCHUNK_DEFAULTHEIGHT);
        // Default height is FIXED32
        size += Integer.BYTES;
        // Hash data is never null
        size += ProtoWriterTools.sizeOfDelimited(FIELD_HASHCHUNK_HASHDATA, hashData.length);
        return size;
    }

    public void writeTo(final WritableSequentialData out) {
        final long pos = out.position();
        ProtoWriterTools.writeTag(out, FIELD_HASHCHUNK_PATH);
        out.writeLong(path);
        // Height is always > 0
        ProtoWriterTools.writeTag(out, FIELD_HASHCHUNK_HEIGHT);
        out.writeInt(height);
        // Default height is always > 0
        ProtoWriterTools.writeTag(out, FIELD_HASHCHUNK_DEFAULTHEIGHT);
        out.writeInt(defaultHeight);
        // Hash data is never null
        ProtoWriterTools.writeTag(out, FIELD_HASHCHUNK_HASHDATA);
        out.writeVarInt(hashData.length, false);
        out.writeBytes(hashData);
        assert out.position() == pos + getSizeInBytes();
    }

    public static long pathToChunkPath(long path, final int chunkHeight) {
        assert path > 0;
        assert chunkHeight > 0;
        final int rankDif = Path.getRank(path) % chunkHeight;
        return Path.getGrandParentPath(path, rankDif == 0 ? chunkHeight : rankDif);
    }

    public long getChunkId() {
        return pathToChunkId(Path.getLeftChildPath(path), defaultHeight);
    }

    /**
     * Returns an ID, starting from 0, of a chunk of the given height, containing
     * the given virtual path.
     *
     * @param path
     *      Path to check
     * @param chunkHeight
     *      Chunk height
     * @return
     *      The chunk ID, starting from 0
     */
    public static long pathToChunkId(final long path, final int chunkHeight) {
        assert path > 0;
        assert chunkHeight > 0;
        long pp = path + 1;
        int z = Long.numberOfLeadingZeros(pp);
        int r = (Long.SIZE - z - 2) % chunkHeight + 1;
        long m = Long.MIN_VALUE >>> (z + r);
        return ((pp >>> r) ^ m) + (m - 1) / ((1L << chunkHeight) - 1);
    }

    public static long chunkPathToChunkId(final long chunkPath, final int chunkHeight) {
        return pathToChunkId(Path.getLeftChildPath(chunkPath), chunkHeight);
    }

    /**
     * Returns a path of chunk with the given ID and height.
     *
     * @param chunkId
     *      Chunk ID, starting from 0
     * @param chunkHeight
     *      Chunk height
     * @return
     *      The chunk path
     */
    public static long chunkIdToChunkPath(final long chunkId, final int chunkHeight) {
        assert chunkHeight > 0;
        if (chunkId == 0) {
            return 0;
        }
        final int childCount = 1 << chunkHeight;
        int chunkRank = 0;
        int chunksAtRank = 1;
        int id = 0;
        while (id < chunkId) {
            chunksAtRank *= childCount;
            id += chunksAtRank;
            chunkRank += chunkHeight;
        }
        return Path.getLeftGrandChildPath(0, chunkRank) + chunkId - id + chunksAtRank - 1;
    }

    /**
     * Returns a number of hashes stored in the chunk.
     *
     * @return
     *      The number of hashes in the chunk
     */
    public int getChunkSize() {
        return getChunkSize(height);
    }

    /**
     * Returns a number of hashes stored in a chunk of the given depth.
     *
     * @param chunkHeight
     *      Chunk height
     * @return
     *      The number of hashes in a chunk
     */
    public static int getChunkSize(final int chunkHeight) {
        assert chunkHeight > 0;
        return 1 << chunkHeight;
    }

    /**
     * Returns an index, starting from 0, of the given path in this chunk. Paths are global,
     * not relative to the chunk. Max index is {@link #getChunkSize()} - 1. If the path is not
     * in the chunk, an {@link IllegalArgumentException} is thrown.
     *
     * @param path
     *      Path to check
     * @return
     *      Path index in the chunk, starting from 0
     */
//    public int getPathIndexInChunk(final long path) {
//        return getPathIndexInChunk(path, this.path, height);
//    }

    /**
     * Returns an index, starting from 0, of the given path in a chunk that starts from the specified
     * first path. Paths are global, not relative to the chunk. Max index is {@link #getChunkSize()} - 1.
     * If the path is not in the chunk, an {@link IllegalArgumentException} is thrown.
     *
     * @param path
     *      Path to check
     * @param chunkPath
     *      Chunk path
     * @param chunkHeight
     *      Chunk height
     * @return
     *      Path index in the chunk, starting from 0
     * @throws IllegalArgumentException
     *      If the path is outside this chunk
     */
    public static int getPathIndexInChunk(final long path, final long chunkPath, final int chunkHeight) {
        final long firstPathInChunk = Path.getLeftGrandChildPath(chunkPath, chunkHeight);
        if ((path < firstPathInChunk) || (path >= firstPathInChunk + getChunkSize(chunkHeight))) {
            throw new IllegalArgumentException("Path " + path + " is not in chunk: " + chunkPath + "/" + chunkHeight);
        }
        return Math.toIntExact(path - firstPathInChunk);
    }

    /**
     * Given a virtual path, returns chunk ID, so that chunks 0 to the ID cover
     * all hashes up to (and including) the path.
     *
     * @param maxPath Virtual path
     * @param chunkHeight Chunk height
     * @return Min chunk ID to cover all paths up to the given path
     */
    public static long minChunkIdForPaths(final long maxPath, final int chunkHeight) {
        assert maxPath > 0;
        // ID of a chunk that contains maxPath
        final long maxPathChunkId = pathToChunkId(maxPath, chunkHeight);
        // Now check what chunk covers the last path at the previous rank. It may
        // be greater than the chunk for maxPath
        final int prevRank = Math.max(1, Path.getRank(maxPath) - 1);
        final long maxPathInPrevRank = Path.getRightGrandChildPath(0, prevRank);
        final long prevRankPathChunkId = pathToChunkId(maxPathInPrevRank, chunkHeight);
        return Math.max(prevRankPathChunkId, maxPathChunkId);
    }

    public long getPath(final int pathIndex) {
        return getPathInChunk(pathIndex, path, height);
    }

    public static long getPathInChunk(int pathIndex, final long chunkPath, final int chunkHeight) {
        final int chunkSize = getChunkSize(chunkHeight);
        if ((pathIndex < 0) || (pathIndex >= chunkSize)) {
            throw new IllegalArgumentException("Wrong path index");
        }
        final long firstPathAtLastLevel = Path.getLeftGrandChildPath(chunkPath, chunkHeight);
        return firstPathAtLastLevel + pathIndex;
    }

    // index must be 0 <= index < chunkSize
    private void setHashImpl(final int index, final Hash hash) {
        final int pos = index * Cryptography.DEFAULT_DIGEST_TYPE.digestLength();
        assert pos < hashData.length;
        final int len = Cryptography.DEFAULT_DIGEST_TYPE.digestLength();
        // No synchronization for reading or writing hashes. Memory visibility has
        // to be ensured by the caller, typically virtual hashing tasks
        hash.getBytes().getBytes(0, hashData, pos, len);
    }

    // index must be 0 <= index < chunkSize
    private Hash getHashImpl(final int index) {
        final int pos = index * Cryptography.DEFAULT_DIGEST_TYPE.digestLength();
        assert pos < hashData.length;
        final int len = Cryptography.DEFAULT_DIGEST_TYPE.digestLength();
        final byte[] hashBytes = new byte[len];
        // No synchronization for reading or writing hashes. Memory visibility has
        // to be ensured by the caller, typically virtual hashing tasks
        System.arraycopy(hashData, pos, hashBytes, 0, len);
        return new Hash(hashBytes, Cryptography.DEFAULT_DIGEST_TYPE);
    }

    /**
     * Updates a hash at the given path. If this hash chunk doesn't contain the path, an
     * {@link IllegalArgumentException} is thrown.
     *
     * <p>This method must not be called in parallel for the same path. Typically, hashes
     * in chunks are updated from virtual hasher tasks, every path is handled by a single
     * hashing task.
     *
     * @param path Virtual path
     * @param hash Hash to set
     */
    public void setHashAtPath(final long path, final Hash hash) {
        final int index = getPathIndexInChunk(path, this.path, height);
        setHashImpl(index, hash);
    }

    /**
     * TODO
     */
    public Hash getHashAtPath(final long path) {
        final int index = getPathIndexInChunk(path, this.path, height);
        return getHashImpl(index);
    }

    /**
     * Updates a hash at the given index. If the index is negative, or greater or equal to
     * the size of the chunk, an {@link IllegalArgumentException} is thrown.
     *
     * <p>This method must not be called in parallel for the same index. Typically, hashes
     * in chunks are updated from virtual hasher tasks, every path / index is handled by a
     * single hashing task.
     *
     * @param index Hash index in the chunk
     * @param hash Hash to set
     */
    public void setHashAtIndex(final int index, final Hash hash) {
        if ((index < 0) || (index >= getChunkSize())) {
            throw new IllegalArgumentException("Wrong hash index: " + index);
        }
        setHashImpl(index, hash);
    }

    public Hash getHashAtIndex(final int index) {
        if ((index < 0) || (index >= getChunkSize())) {
            throw new IllegalArgumentException("Wrong hash index: " + index);
        }
        return getHashImpl(index);
    }
}
