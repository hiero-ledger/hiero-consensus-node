// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.nft;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.io.exceptions.InvalidVersionException;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A unique identifier for an {@link Nft}.
 */
public class MerkleNftId extends PartialMerkleLeaf implements MerkleLeaf {

    public static final long CLASS_ID = 0xaf558e8b56087253L;

    private static final int VERSION_ORIGINAL = 1;

    // These values are intentionally stored as primitives to avoid the overhead of an internal object.
    private long shardNum;
    private long realmNum;
    private long tokenNum;

    /**
     * Create a new token ID.
     */
    public MerkleNftId() {}

    /**
     * Create a new token ID.
     *
     * @param nftId
     * 		the ID of the NFT
     */
    public MerkleNftId(final NftId nftId) {
        this.shardNum = nftId.getShardNum();
        this.realmNum = nftId.getRealmNum();
        this.tokenNum = nftId.getTokenNum();
    }

    /**
     * Create a new token ID.
     *
     * @param shardNum
     * 		the shard number
     * @param realmNum
     * 		the realm number
     * @param tokenNum
     * 		the token number
     */
    public MerkleNftId(final long shardNum, final long realmNum, final long tokenNum) {
        this.shardNum = shardNum;
        this.realmNum = realmNum;
        this.tokenNum = tokenNum;
    }

    /**
     * Copy constructor.
     */
    private MerkleNftId(final MerkleNftId nftId) {
        super(nftId);
        setNftId(nftId.getNftId());
        nftId.setImmutable(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(this.shardNum);
        out.writeLong(this.realmNum);
        out.writeLong(this.tokenNum);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        if (version != VERSION_ORIGINAL) {
            throw new InvalidVersionException(VERSION_ORIGINAL, version);
        }

        final NftId nftId = in.readSerializable(false, NftId::new);
        setNftId(nftId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return VERSION_ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNftId copy() {
        return new MerkleNftId(this);
    }

    /**
     * Create a deep copy. Does not care about mutability status, and will not make the original immutable.
     */
    public MerkleNftId deepCopy() {
        return new MerkleNftId(shardNum, realmNum, tokenNum);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final MerkleNftId that = (MerkleNftId) other;
        return shardNum == that.shardNum && realmNum == that.realmNum && tokenNum == that.tokenNum;
    }

    /**
     * Get the identifying key for an NFT.
     */
    public NftId getNftId() {
        return new NftId(shardNum, realmNum, tokenNum);
    }

    /**
     * Set the identifying key for an NFT.
     */
    public void setNftId(final NftId nftId) {
        shardNum = nftId.getShardNum();
        realmNum = nftId.getRealmNum();
        tokenNum = nftId.getTokenNum();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(shardNum, realmNum, tokenNum);
    }

    /**
     * {@inheritDoc}
     */
    public String toString() {
        return new ToStringBuilder(this)
                .append("ShardNum", shardNum)
                .append("RealmNum", realmNum)
                .append("TokenNum", tokenNum)
                .toString();
    }
}
