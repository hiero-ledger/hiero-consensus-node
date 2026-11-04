// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.hashOfAll;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.RECORD_HASH_SIZE;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;

/**
 * Utility methods for block implementation.
 */
public class BlockImplUtils {
    public static final byte[] LEAF_PREFIX = {0x0};
    public static final Bytes LEAF_PREFIX_BYTES = Bytes.wrap(LEAF_PREFIX);
    public static final byte[] INTERNAL_NODE_PREFIX = {0x2};
    public static final Bytes INTERNAL_NODE_PREFIX_BYTES = Bytes.wrap(INTERNAL_NODE_PREFIX);

    /**
     * Prevent instantiation
     */
    private BlockImplUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Appends the given hash to the given hashes. If the number of hashes exceeds the given maximum, the oldest hash
     * is removed.
     * @param hash the hash to append
     * @param hashes the hashes
     * @param maxHashes the maximum number of hashes
     * @return the new hashes
     */
    public static Bytes appendHash(@NonNull final Bytes hash, @NonNull final Bytes hashes, final int maxHashes) {
        final var hashLen = (int) hash.length();
        final var limit = hashLen * maxHashes;
        final byte[] bytes = hashes.toByteArray();
        final byte[] newBytes;
        if (bytes.length < limit) {
            newBytes = new byte[bytes.length + hashLen];
            System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
            hash.getBytes(0, newBytes, newBytes.length - hashLen, hashLen);
        } else {
            newBytes = bytes;
            System.arraycopy(newBytes, hashLen, newBytes, 0, newBytes.length - hashLen);
            hash.getBytes(0, newBytes, newBytes.length - hashLen, hashLen);
        }
        return Bytes.wrap(newBytes);
    }

    /**
     * Given a concatenated sequence of fixed-size block hashes, where the rightmost hash was for the given last block
     * number, returns either the hash of the block at the given block number, or null if the block number is out of
     * range.
     *
     * @param blockHashes the concatenated sequence of block hashes
     * @param lastBlockNo the block number of the rightmost hash in the sequence
     * @param blockNo the block number of the hash to return
     * @param hashSize the byte length of each individual hash in the sequence
     * @return the hash of the block at the given block number if available, null otherwise
     */
    public static @Nullable Bytes blockHashByBlockNumber(
            @NonNull final Bytes blockHashes, final long lastBlockNo, final long blockNo, final int hashSize) {
        final var blocksAvailable = blockHashes.length() / hashSize;

        // Smart contracts (and other services) call this API. Should a smart contract call this, we don't really
        // want to throw an exception. So we will just return null, which is also valid. Basically, if the block
        // doesn't exist, you get null.
        if (blockNo < 0) {
            return null;
        }
        final var firstAvailableBlockNo = lastBlockNo - blocksAvailable + 1;
        // If blocksAvailable == 0, then firstAvailable == blockNo; and all numbers are
        // either less than or greater than or equal to blockNo, so we return unavailable
        if (blockNo < firstAvailableBlockNo || blockNo > lastBlockNo) {
            return null;
        } else {
            long offset = (blockNo - firstAvailableBlockNo) * hashSize;
            return blockHashes.slice(offset, hashSize);
        }
    }

    /**
     * Left-pads the given hash with leading zero bytes so that it occupies exactly {@code storedSize} bytes. Returns the
     * hash unchanged if it is already {@code storedSize} bytes long. This lets a shorter (e.g. 32-byte SHA-256) block
     * hash be stored uniformly alongside full-size (48-byte SHA-384) entries in {@code trailingBlockHashes}.
     *
     * @param hash the hash to pad
     * @param storedSize the uniform byte length each stored entry must occupy
     * @return the left-padded hash
     * @throws IllegalArgumentException if the hash is longer than {@code storedSize}
     */
    public static Bytes leftPadTo(@NonNull final Bytes hash, final int storedSize) {
        requireNonNull(hash);
        final int hashLen = (int) hash.length();
        if (hashLen == storedSize) {
            return hash;
        }
        if (hashLen > storedSize) {
            throw new IllegalArgumentException("Hash of length " + hashLen + " exceeds stored size " + storedSize);
        }
        final byte[] out = new byte[storedSize];
        hash.getBytes(0, out, storedSize - hashLen, hashLen);
        return Bytes.wrap(out);
    }

    /**
     * Returns a record-stream running hash sized to the full {@code RECORD_HASH_SIZE}, substituting a zero-filled
     * value for an absent (empty) predecessor hash. Used when seeding the post-cutover {@code trailingOutputHashes}:
     * the record {@code RunningHashes} may have empty {@code n-1..n-3} slots (e.g. before four record blocks ever
     * existed). Zero-filling them keeps the reseeded window at exactly four entries, so the post-cutover
     * length-decode (which assumes a mixed window is exactly four entries) stays unambiguous even when the first
     * post-cutover block produces fewer than four results. A zero predecessor reads as zeros downstream (e.g. the
     * PRNG seed), matching existing behavior.
     *
     * @param runningHash a record-stream running hash, possibly empty
     * @return the hash itself if non-empty, otherwise {@code RECORD_HASH_SIZE} zero bytes
     */
    public static Bytes fullRecordRunningHash(@NonNull final Bytes runningHash) {
        requireNonNull(runningHash);
        return runningHash.length() == 0 ? Bytes.wrap(new byte[RECORD_HASH_SIZE]) : runningHash;
    }

    /**
     * Reverses {@link #leftPadTo(Bytes, int)} for a single stored entry. If the leading {@code stored.length() -
     * realSize} bytes are all zero, the entry was a padded shorter hash and its real {@code realSize}-byte value is
     * returned; otherwise the entry is a genuine full-size hash (e.g. a pre-cutover SHA-384 record hash) and is returned
     * unchanged.
     *
     * @param stored the stored, possibly padded, entry
     * @param realSize the byte length of the active digest's hashes
     * @return the real hash
     */
    public static Bytes stripLeadingPad(@NonNull final Bytes stored, final int realSize) {
        requireNonNull(stored);
        final int storedLen = (int) stored.length();
        if (storedLen <= realSize) {
            return stored;
        }
        // LOAD-BEARING INVARIANT - do not optimize away: a genuine full-size hash (e.g. pre-cutover SHA-384)
        // effectively never begins with a full run of zero bytes (2^-128 for 48->32), so an all-zero leading region
        // reliably identifies a padded shorter hash.
        for (int i = 0; i < storedLen - realSize; i++) {
            if (stored.getByte(i) != 0) {
                return stored;
            }
        }
        return stored.slice(storedLen - realSize, realSize);
    }

    /**
     * The result of appending to a trailing window: the updated window bytes and whether that window is now stored at
     * the active digest's native size (its {@code trailing_block_hashes_compacted} flag).
     *
     * @param window the updated trailing block hashes
     * @param compacted whether {@code window} is stored at {@code activeSize} native entries (vs. 48-byte padded)
     */
    public record AppendResult(@NonNull Bytes window, boolean compacted) {}

    /**
     * Reads the real (unpadded) hash of a block from a uniform trailing window whose stride is given directly by the
     * persisted {@code compacted} flag — no entry count is needed. When {@code compacted} is true the window is stored
     * at the active digest's native size; otherwise it is stored at the pre-cutover {@code RECORD_HASH_SIZE} (48-byte)
     * padded size. This is the cold-read entry point (restart, reconnect, snapshot query), where the flag read from
     * {@code BlockStreamInfo} is authoritative and length/count inference is both unnecessary and (for a window whose
     * length divides evenly by both sizes) ambiguous.
     *
     * @param window the concatenated trailing block hashes
     * @param lastBlockNo the block number of the rightmost entry in the window
     * @param blockNo the block number of the hash to return
     * @param activeSize the byte length of the active digest's hashes
     * @param compacted whether the window is stored at {@code activeSize} native entries
     * @return the real hash of the requested block, or null if out of range
     */
    public static @Nullable Bytes realBlockHashByBlockNumber(
            @NonNull final Bytes window,
            final long lastBlockNo,
            final long blockNo,
            final int activeSize,
            final boolean compacted) {
        final int stride = compacted ? activeSize : RECORD_HASH_SIZE;
        final var stored = blockHashByBlockNumber(window, lastBlockNo, blockNo, stride);
        return stored == null ? null : stripLeadingPad(stored, activeSize);
    }

    /**
     * Appends a new native block hash to the trailing window, keeping all entries a uniform size, using the persisted
     * {@code compacted} flag as the authoritative stride rather than re-inferring it from the entry count. While the
     * window is padded ({@code compacted == false}) shorter hashes are left-padded to {@code recordHashSize}; once the oldest
     * entry is itself padded (no genuine hashes remain) <b>and the block stream has cut over</b>, the window is
     * compacted once to {@code activeSize} and {@code compacted} flips to true for this and all subsequent appends.
     * Before cutover the window is never compacted. A {@code recordHashSize == activeSize} window stores full-size entries,
     * so it is never marked compacted; a later switch to a shorter digest then pads instead of misreading it.
     *
     * @param newHash the new native block hash to append
     * @param window the current trailing block hashes
     * @param maxHashes the maximum number of entries the window retains
     * @param recordHashSize the pre-cutover (full-size) stored entry length
     * @param activeSize the byte length of the active digest's hashes
     * @param postCutover whether the block stream has cut over to the active digest; compaction only occurs if true
     * @param compacted whether {@code window} is already stored at {@code activeSize} native entries
     * @return the updated window and its new compacted flag
     */
    public static AppendResult appendTrailingBlockHash(
            @NonNull final Bytes newHash,
            @NonNull final Bytes window,
            final int maxHashes,
            final int recordHashSize,
            final int activeSize,
            final boolean postCutover,
            final boolean compacted) {
        if (recordHashSize == activeSize) {
            // Entries are already stored at the full (uncompacted) size, so the window is not compacted
            return new AppendResult(appendHash(leftPadTo(newHash, activeSize), window, maxHashes), false);
        }
        if (compacted) {
            // Already native: append the (native-size) hash directly and stay compacted.
            return new AppendResult(appendHash(newHash, window, maxHashes), true);
        }
        if (postCutover && window.length() > 0 && isLeftmostPadded(window, recordHashSize - activeSize)) {
            // Padded, post-cutover, and no genuine (pre-cutover) hash remains: compact once to native and stay native.
            final var stripped = stripWindowPadding(window, recordHashSize, activeSize);
            return new AppendResult(appendHash(newHash, stripped, maxHashes), true);
        }
        // Still padded: keep storing at the pre-cutover size (left-padding the new native hash).
        return new AppendResult(appendHash(leftPadTo(newHash, recordHashSize), window, maxHashes), false);
    }

    /** Returns true if the leading {@code padLen} bytes of the window (the leftmost entry's pad region) are all zero. */
    private static boolean isLeftmostPadded(final Bytes window, final int padLen) {
        for (int i = 0; i < padLen; i++) {
            if (window.getByte(i) != 0) {
                return false;
            }
        }
        return true;
    }

    /** Compacts a window of {@code preSize}-byte padded entries to {@code activeSize}-byte native entries. */
    private static Bytes stripWindowPadding(final Bytes window, final int preSize, final int activeSize) {
        final int k = (int) (window.length() / preSize);
        final byte[] out = new byte[k * activeSize];
        for (int i = 0; i < k; i++) {
            window.getBytes((long) i * preSize + (preSize - activeSize), out, i * activeSize, activeSize);
        }
        return Bytes.wrap(out);
    }

    /**
     * Renders both trailing-hash windows in a semi-human-readable form: per field, the entry count and
     * stride/sizes, then one real (unpadded) hash per line. Used to log a decoded snapshot of the windows at the
     * digest transition and the block-stream cutover, so the exact values on both sides are inspectable without a
     * giant single-line hex blob.
     *
     * @param label a short description of the snapshot (e.g. {@code "BEFORE cutover"})
     * @param trailingBlockHashes the stored block-hash window
     * @param trailingOutputHashes the stored running-hash window
     * @param lastBlockNo the block number of the rightmost block-hash entry
     * @param activeSize the byte length of the active digest's hashes
     * @return the formatted multi-line description
     */
    public static String describeTrailingWindows(
            @NonNull final String label,
            @NonNull final Bytes trailingBlockHashes,
            @NonNull final Bytes trailingOutputHashes,
            final long lastBlockNo,
            final int activeSize) {
        final var sb = new StringBuilder(256);
        sb.append("Trailing hashes ")
                .append(label)
                .append(" [afterBlock=")
                .append(lastBlockNo)
                .append("]\n");

        // These windows are only ever logged pre-compaction (at the digest transition or cutover; compaction
        // happens ~256 blocks later), so a length divisible by the 48-byte stored size is a 48-byte window —
        // genuine SHA-384 or shorter hashes left-padded to 48 — and anything else is native. Deciding by
        // divisibility alone avoids the entry-count ambiguity that mis-rounds a length divisible by both 48 and the
        // active size (e.g. 194 * 48 == 291 * 32).
        final long blockLen = trailingBlockHashes.length();
        final int blockStride = blockLen == 0 ? 0 : (blockLen % RECORD_HASH_SIZE == 0 ? RECORD_HASH_SIZE : activeSize);
        final int blockCount = blockStride == 0 ? 0 : (int) (blockLen / blockStride);
        // A 48-byte stride is the uncompacted form — entries are genuine 48-byte hashes or shorter hashes
        // left-padded to 48. Compaction produces a shorter (active-size) stride. Decide purely on the stride, not
        // the active digest length, which is unreliable at a config boundary (e.g. read during the cutover restart).
        final boolean preCompaction = blockStride == RECORD_HASH_SIZE;
        sb.append("  trailingBlockHashes: count=")
                .append(blockCount)
                .append(" stride=")
                .append(blockStride)
                .append("B ")
                .append(preCompaction ? "(pre-compaction)" : "(native)")
                .append('\n');
        for (int i = 0; i < blockCount; i++) {
            final long blockNo = lastBlockNo - blockCount + 1 + i;
            final var real =
                    stripLeadingPad(trailingBlockHashes.slice((long) i * blockStride, blockStride), activeSize);
            sb.append("    block ")
                    .append(blockNo)
                    .append(": ")
                    .append(real.toHex())
                    .append('\n');
        }

        // The 4-entry running-hash window is small, and its per-entry byte sizes are ambiguous to decode from the
        // blob alone (a 48-byte window could be one SHA-384 entry; the reseed may hold fewer than four when the
        // record stream's older running hashes are empty). Log its exact length and raw hex — both human-checkable
        // and the exact value — rather than guessing a per-entry split.
        sb.append("  trailingOutputHashes: length=")
                .append(trailingOutputHashes.length())
                .append("B: ")
                .append(trailingOutputHashes.toHex())
                .append('\n');
        return sb.toString();
    }

    public static Bytes hashLeaf(@NonNull final MessageDigest digest, @NonNull final Bytes leafData) {
        return hashOfAll(digest, LEAF_PREFIX_BYTES, leafData);
    }

    public static byte[] hashLeaf(@NonNull final MessageDigest digest, @NonNull final byte[] leafData) {
        return hashOfAll(digest, LEAF_PREFIX, leafData);
    }

    public static byte[] hashInternalNode(
            @NonNull final MessageDigest digest, @NonNull final byte[] leftHash, @NonNull final byte[] rightHash) {
        return hashOfAll(digest, INTERNAL_NODE_PREFIX, leftHash, rightHash);
    }

    public static Bytes hashInternalNode(
            @NonNull final MessageDigest digest, @NonNull final Bytes leftHash, @NonNull final Bytes rightHash) {
        requireNonNull(digest);
        requireNonNull(leftHash);
        requireNonNull(rightHash);
        return hashOfAll(digest, INTERNAL_NODE_PREFIX_BYTES, leftHash, rightHash);
    }

    public static Bytes hashInternalNode(
            @NonNull final MessageDigest digest, @NonNull final Bytes leftHash, @NonNull final byte[] rightHash) {
        requireNonNull(digest);
        requireNonNull(leftHash);
        requireNonNull(rightHash);
        INTERNAL_NODE_PREFIX_BYTES.writeTo(digest);
        leftHash.writeTo(digest);
        return Bytes.wrap(digest.digest(rightHash));
    }

    public static byte[] combine(
            @NonNull final MessageDigest digest, @NonNull final byte[] leftHash, @NonNull final byte[] rightHash) {
        return hashOfAll(digest, leftHash, rightHash);
    }

    public static Bytes combine(
            @NonNull final MessageDigest digest, @NonNull final Bytes leftHash, @NonNull final Bytes rightHash) {
        requireNonNull(digest);
        requireNonNull(leftHash);
        requireNonNull(rightHash);
        return hashOfAll(digest, leftHash, rightHash);
    }
}
