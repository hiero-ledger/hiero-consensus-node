// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static com.hedera.node.app.hapi.utils.ethereum.EthTxSigs.extractAuthoritySignature;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Longs;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import org.apache.commons.codec.binary.Hex;
import org.apache.tuweni.bytes.Bytes;

/**
 * Represents one item in an authorization list for a code delegation (EIP-7702) transaction.
 */
public record CodeDelegation(byte[] chainId, byte[] address, long nonce, int recId, byte[] r, byte[] s) {

    public Optional<EthTxSigs> computeAuthority() {
        return Optional.ofNullable(extractAuthoritySignature(this));
    }

    public long getChainId() {
        return chainId == null ? 0 : new java.math.BigInteger(1, chainId).longValue();
    }

    /*
     * Calculates the signable message for this code delegation.
     *
     */
    public byte[] calculateSignableMessage() {
        return RLPEncoder.sequence(chainId, address, Longs.toByteArray(nonce));
    }

    public BigInteger getS() {
        return Bytes.wrap(s).toUnsignedBigInteger();
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;

        final CodeDelegation ethTxData = (CodeDelegation) other;

        return (nonce == ethTxData.nonce)
                && (Arrays.equals(address, ethTxData.address))
                && (Arrays.equals(chainId, ethTxData.chainId))
                && (recId == ethTxData.recId)
                && (Arrays.equals(r, ethTxData.r))
                && (Arrays.equals(s, ethTxData.s));
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(chainId);
        result = 31 * result + Arrays.hashCode(address);
        result = 31 * result + Long.hashCode(nonce);
        result = 31 * result + recId;
        result = 31 * result + Arrays.hashCode(r);
        result = 31 * result + Arrays.hashCode(s);
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("chainId", chainId == null ? null : Hex.encodeHexString(chainId))
                .add("address", address == null ? null : Hex.encodeHexString(address))
                .add("nonce", nonce)
                .add("recId", recId)
                .add("r", Hex.encodeHexString(r))
                .add("s", Hex.encodeHexString(s))
                .toString();
    }
}
