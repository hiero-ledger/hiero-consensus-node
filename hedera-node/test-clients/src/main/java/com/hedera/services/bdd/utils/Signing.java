// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.utils;

import static com.hedera.node.app.hapi.utils.ethereum.CodeDelegation.MAGIC;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType.EIP7702;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType.LEGACY_ETHEREUM;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.util.Integers;
import com.hedera.cryptography.libsecp256k1.ContextualLibsecp256k1;
import com.hedera.cryptography.libsecp256k1.Libsecp256k1;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.foreign.MemorySegment;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

/**
 * Utility methods for signing messages
 */
public final class Signing {
    private static final ContextualLibsecp256k1 LIBSECP256K1 = ContextualLibsecp256k1.getInstance();

    public static EthTxData signMessage(EthTxData ethTx, byte[] privateKey, boolean flipRecId) {
        return signMessageInternal(ethTx, privateKey, flipRecId);
    }

    public static EthTxData signMessage(EthTxData ethTx, byte[] privateKey) {
        return signMessageInternal(ethTx, privateKey, false);
    }

    private static EthTxData signMessageInternal(EthTxData ethTx, byte[] privateKey, boolean flipRecId) {
        byte[] signableMessage = EthTxSigs.calculateSignableMessage(ethTx);
        final var sigBytes =
                extractSignatureBytes(signableMessage, ethTx.type(), ethTx.chainId(), privateKey, flipRecId);

        return new EthTxData(
                ethTx.rawTx(),
                ethTx.type(),
                ethTx.chainId(),
                ethTx.nonce(),
                ethTx.gasPrice(),
                ethTx.maxPriorityGas(),
                ethTx.maxGas(),
                ethTx.gasLimit(),
                ethTx.to(),
                ethTx.value(),
                ethTx.callData(),
                ethTx.accessList(),
                ethTx.accessListAsRlp(),
                ethTx.authorizationList(),
                ethTx.authorizationListAsRlp(),
                sigBytes.recId,
                sigBytes.v,
                sigBytes.r,
                sigBytes.s);
    }

    public static byte[] signMessage(final byte[] messageHash, byte[] privateKey) {
        final byte[] signature = new byte[Libsecp256k1.RECOVERABLE_SIGNATURE_BYTES];
        final MemorySegment signatureSeg = MemorySegment.ofArray(signature);
        LIBSECP256K1.secp256k1EcdsaSignRecoverable(
                signatureSeg,
                MemorySegment.ofArray(messageHash),
                MemorySegment.ofArray(privateKey),
                MemorySegment.NULL,
                MemorySegment.NULL);

        final byte[] sig = new byte[Libsecp256k1.SIGNATURE_BYTES];
        final int[] recId = new int[1];
        LIBSECP256K1.secp256k1EcdsaRecoverableSignatureSerializeCompact(
                MemorySegment.ofArray(sig), MemorySegment.ofArray(recId), signatureSeg);

        final byte[] result = new byte[65];
        System.arraycopy(sig, 0, result, 0, 64);
        result[64] = (byte) (recId[0] + 27);
        return result;
    }

    public static byte[] signMessageEd25519(final byte[] message, byte[] privateKey) {
        byte[] signature = new byte[Ed25519.SIGNATURE_SIZE];
        Ed25519.sign(privateKey, 0, message, 0, message.length, signature, 0);

        return signature;
    }

    public static Object[] signCodeDelegation(
            final byte[] chainId,
            final Address delegationTarget,
            final long nonce,
            byte[] privateKey,
            boolean flipRecId) {
        final var byteAddress = asEvmAddress(delegationTarget.value().longValue());
        final var codeDelegation =
                RLPEncoder.list(chainId, byteAddress, Bytes.minimalBytes(nonce).toArray());

        final var signableMessage = Bytes.concatenate(MAGIC, Bytes.wrap(codeDelegation));
        final var extractedBytes =
                extractSignatureBytes(signableMessage.toArray(), EIP7702, chainId, privateKey, flipRecId);

        return new Object[] {
            chainId,
            byteAddress,
            Bytes.minimalBytes(nonce).toArray(),
            Integers.toBytes(extractedBytes.recId()),
            extractedBytes.r(),
            extractedBytes.s()
        };
    }

    private static SignatureBytes extractSignatureBytes(
            byte[] signableMessage, EthTransactionType type, byte[] chainId, byte[] privateKey, boolean flipRecId) {
        final byte[] signature = new byte[Libsecp256k1.RECOVERABLE_SIGNATURE_BYTES];
        final MemorySegment signatureSeg = MemorySegment.ofArray(signature);
        LIBSECP256K1.secp256k1EcdsaSignRecoverable(
                signatureSeg,
                MemorySegment.ofArray(MiscCryptoUtils.keccak256DigestOf(signableMessage)),
                MemorySegment.ofArray(privateKey),
                MemorySegment.NULL,
                MemorySegment.NULL);

        final byte[] sig = new byte[Libsecp256k1.SIGNATURE_BYTES];
        final int[] recId = new int[1];
        LIBSECP256K1.secp256k1EcdsaRecoverableSignatureSerializeCompact(
                MemorySegment.ofArray(sig), MemorySegment.ofArray(recId), signatureSeg);

        // wrap in signature object
        final byte[] r = new byte[32];
        System.arraycopy(sig, 0, r, 0, 32);
        final byte[] s = new byte[32];
        System.arraycopy(sig, 32, s, 0, 32);

        BigInteger val;
        // calculations originate from https://eips.ethereum.org/EIPS/eip-155
        if (type == LEGACY_ETHEREUM) {
            if (chainId == null || chainId.length == 0) {
                val = BigInteger.valueOf(27L + recId[0]);
            } else {
                val = BigInteger.valueOf(35L + recId[0]).add(new BigInteger(1, chainId).multiply(BigInteger.TWO));
            }
        } else {
            val = null;
        }

        return new SignatureBytes(
                r, s, val != null ? val.toByteArray() : null, flipRecId ? ((byte) recId[0]) ^ 1 : (byte) recId[0]);
    }

    private Signing() {}

    private record SignatureBytes(
            @NonNull byte[] r, @NonNull byte[] s, @Nullable byte[] v, int recId) {

        public Bytes codeDelegationSigBytes() {
            return Bytes.concatenate(Bytes.of(recId), Bytes.of(r), Bytes.of(s));
        }
    }
}
