// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.BC_PROVIDER;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.libsecp256k1.ContextualLibsecp256k1;
import com.hedera.cryptography.libsecp256k1.Libsecp256k1;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;

/**
 * Useful methods for interacting with SECP256K1 ECDSA keys.
 */
public class Secp256k1Utils {
    private static final ContextualLibsecp256k1 LIBSECP256K1 = ContextualLibsecp256k1.getInstance();

    public static final int ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH = 33;

    private static final int EVM_ADDRESS_BYTE_LENGTH = 20;
    private static final byte ODD_PARITY = (byte) 0x03;
    private static final byte EVEN_PARITY = (byte) 0x02;

    static boolean isValidEvmAddress(@NonNull final ContractID contractId) {
        return contractId.contractNumOrElse(0L) > 0
                || contractId.evmAddressOrElse(Bytes.EMPTY).length() == EVM_ADDRESS_BYTE_LENGTH;
    }

    private record Cache(byte[] pubkey, MemorySegment pubkeySeg, long[] len, MemorySegment lenSeg) {}

    private static final ThreadLocal<Cache> CACHE = new ThreadLocal<>() {
        @Override
        protected Cache initialValue() {
            final byte[] pubkey = new byte[Libsecp256k1.PUBLIC_KEY_BYTES];
            final long[] len = new long[1];
            return new Cache(pubkey, MemorySegment.ofArray(pubkey), len, MemorySegment.ofArray(len));
        }
    };

    public static byte[] extractEcdsaPublicKey(final ECPrivateKey key) {
        final Cache cache = CACHE.get();

        if (LIBSECP256K1.secp256k1EcPubkeyCreate(
                        cache.pubkeySeg, MemorySegment.ofArray(asPrivateKeyByteArray32(key.getS())))
                != 1) {
            throw new IllegalArgumentException("secp256k1EcPubkeyCreate failed. The private key is probably invalid.");
        }

        final byte[] serializedPubkey = new byte[ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH];
        cache.len[0] = serializedPubkey.length;
        if (LIBSECP256K1.secp256k1EcPubkeySerialize(
                                MemorySegment.ofArray(serializedPubkey),
                                cache.lenSeg,
                                cache.pubkeySeg,
                                Libsecp256k1.SECP256K1_EC_COMPRESSED)
                        != 1
                || cache.len[0] != serializedPubkey.length) {
            throw new IllegalArgumentException(
                    "secp256k1EcPubkeySerialize failed. The private key is probably invalid.");
        }

        return serializedPubkey;
    }

    /// Returns an unsigned byte[] representation of a BigInteger, dropping the leading zero byte
    /// if present, aligning remaining bytes to the right, and padding with zeros to 32 bytes.
    public static byte[] asPrivateKeyByteArray32(final BigInteger value) {
        final byte[] bytes = value.toByteArray();

        if (bytes.length == 32) {
            return bytes;
        } else {
            final byte[] tmp = new byte[32];

            if (bytes.length == 33 && bytes[0] == 0) {
                System.arraycopy(bytes, 1, tmp, 0, 32);
                return tmp;
            } else if (bytes.length < 32) {
                System.arraycopy(bytes, 0, tmp, 32 - bytes.length, bytes.length);
                return tmp;
            }
        }

        // The byte array is longer than 33 bytes. It's an invalid secp256k1 key.
        throw new IllegalArgumentException("Invalid BigInteger that is too long. It cannot be a valid private key.");
    }

    public static byte[] getEvmAddressFromString(final Key key) {
        return extractEcdsaPublicKey(key);
    }

    public static byte[] extractEcdsaPublicKey(final Key key) {
        return key.getECDSASecp256K1().toByteArray();
    }

    public static ECPrivateKey readECKeyFrom(final File pem, final String passphrase) {
        return KeyUtils.readKeyFrom(pem, passphrase, BC_PROVIDER);
    }

    public static ECPrivateKey readECKeyFrom(@NonNull final InputStream in, @NonNull final String passphrase) {
        requireNonNull(in);
        requireNonNull(passphrase);
        return KeyUtils.readKeyFrom(in, passphrase, BC_PROVIDER);
    }

    static boolean isValidEcdsaSecp256k1Key(@NonNull final Bytes key) {
        return key.length() == ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH
                && (key.getByte(0) == EVEN_PARITY || key.getByte(0) == ODD_PARITY);
    }

    public static ECPrivateKey readECKeyFrom(final byte[] keyBytes) {
        final BigInteger s = new BigInteger(1, keyBytes);
        final ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
        final ECPrivateKeySpec keySpec = new ECPrivateKeySpec(s, ecSpec);

        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("EC", BC_PROVIDER);
            return (ECPrivateKey) keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
