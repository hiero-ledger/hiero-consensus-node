// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static com.hedera.node.app.hapi.utils.ethereum.EthTxSigs.extractAuthoritySignature;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Longs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import org.apache.commons.codec.binary.Hex;
import org.apache.tuweni.bytes.Bytes;

/**
 * Represents one item in an authorization list for a code delegation (EIP-7702) transaction.
 */
public record CodeDelegation(byte[] chainId, byte[] address, long nonce, int yParity, byte[] r, byte[] s) {

    public static final Bytes MAGIC = Bytes.fromHexString("05");

    public Optional<EthTxSigs> computeAuthority() {
        return extractAuthoritySignature(this);
    }

    @SuppressWarnings("java:S6207")
    public CodeDelegation {
        requireNonNull(chainId);
        requireNonNull(address);
        requireNonNull(r);
        requireNonNull(s);
    }

    public long getChainId() {
        return new java.math.BigInteger(1, chainId).longValue();
    }

    /*
     * Calculates the signable message for this code delegation.
     *
     */
    public byte[] calculateSignableMessage() {
        return Bytes.concatenate(MAGIC, Bytes.wrap(RLPEncoder.sequence(chainId, address, Longs.toByteArray(nonce))))
                .toArray();
    }

    public BigInteger getS() {
        return Bytes.wrap(s).toUnsignedBigInteger();
    }

    public BigInteger getR() {
        return Bytes.wrap(r).toUnsignedBigInteger();
    }

    public int getYParity() {
        return yParity;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;

        final CodeDelegation ethTxData = (CodeDelegation) other;

        return (nonce == ethTxData.nonce)
                && (Arrays.equals(address, ethTxData.address))
                && (Arrays.equals(chainId, ethTxData.chainId))
                && (yParity == ethTxData.yParity)
                && (Arrays.equals(r, ethTxData.r))
                && (Arrays.equals(s, ethTxData.s));
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(chainId);
        result = 31 * result + Arrays.hashCode(address);
        result = 31 * result + Long.hashCode(nonce);
        result = 31 * result + yParity;
        result = 31 * result + Arrays.hashCode(r);
        result = 31 * result + Arrays.hashCode(s);
        return result;
    }

    @Override
    @NonNull
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("chainId", chainId == null ? null : Hex.encodeHexString(chainId))
                .add("address", address == null ? null : Hex.encodeHexString(address))
                .add("nonce", nonce)
                .add("yParity", yParity)
                .add("r", Hex.encodeHexString(r))
                .add("s", Hex.encodeHexString(s))
                .toString();
    }
}
