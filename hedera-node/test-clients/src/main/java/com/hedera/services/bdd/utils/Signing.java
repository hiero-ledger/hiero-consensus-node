// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.utils;

import static com.hedera.node.app.hapi.utils.ethereum.CodeDelegation.MAGIC;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType.EIP7702;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType.LEGACY_ETHEREUM;
import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.CONTEXT;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.util.Integers;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.sun.jna.ptr.IntByReference;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import org.apache.tuweni.bytes.Bytes;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

/**
 * Utility methods for signing messages
 */
public final class Signing {

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
                null,
                ethTx.authorizationList(),
                ethTx.authorizationListAsRlp(),
                sigBytes.recId,
                sigBytes.v,
                sigBytes.r,
                sigBytes.s);
    }

    public static byte[] signMessage(final byte[] messageHash, byte[] privateKey) {
        final LibSecp256k1.secp256k1_ecdsa_recoverable_signature signature =
                new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        LibSecp256k1.secp256k1_ecdsa_sign_recoverable(CONTEXT, signature, messageHash, privateKey, null, null);

        final ByteBuffer compactSig = ByteBuffer.allocate(64);
        final IntByReference recId = new IntByReference(0);
        LibSecp256k1.secp256k1_ecdsa_recoverable_signature_serialize_compact(
                LibSecp256k1.CONTEXT, compactSig, recId, signature);
        compactSig.flip();
        final byte[] sig = compactSig.array();

        final byte[] result = new byte[65];
        System.arraycopy(sig, 0, result, 0, 64);
        result[64] = (byte) (recId.getValue() + 27);
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
        final var byteAddress = delegationTarget.value().toByteArray();
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
        final LibSecp256k1.secp256k1_ecdsa_recoverable_signature signature =
                new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        LibSecp256k1.secp256k1_ecdsa_sign_recoverable(
                CONTEXT, signature, new Keccak.Digest256().digest(signableMessage), privateKey, null, null);

        final ByteBuffer compactSig = ByteBuffer.allocate(64);
        final IntByReference recId = new IntByReference(0);
        LibSecp256k1.secp256k1_ecdsa_recoverable_signature_serialize_compact(
                LibSecp256k1.CONTEXT, compactSig, recId, signature);
        compactSig.flip();
        final byte[] sig = compactSig.array();

        // wrap in signature object
        final byte[] r = new byte[32];
        System.arraycopy(sig, 0, r, 0, 32);
        final byte[] s = new byte[32];
        System.arraycopy(sig, 32, s, 0, 32);

        BigInteger val;
        // calculations originate from https://eips.ethereum.org/EIPS/eip-155
        if (type == LEGACY_ETHEREUM) {
            if (chainId == null || chainId.length == 0) {
                val = BigInteger.valueOf(27L + recId.getValue());
            } else {
                val = BigInteger.valueOf(35L + recId.getValue())
                        .add(new BigInteger(1, chainId).multiply(BigInteger.TWO));
            }
        } else {
            val = null;
        }

        return new SignatureBytes(
                r,
                s,
                val != null ? val.toByteArray() : null,
                flipRecId ? ((byte) recId.getValue()) ^ 1 : (byte) recId.getValue());
    }

    private Signing() {}

    private record SignatureBytes(
            @NonNull byte[] r, @NonNull byte[] s, @Nullable byte[] v, int recId) {

        public Bytes codeDelegationSigBytes() {
            return Bytes.concatenate(Bytes.of(recId), Bytes.of(r), Bytes.of(s));
        }
    }
}
