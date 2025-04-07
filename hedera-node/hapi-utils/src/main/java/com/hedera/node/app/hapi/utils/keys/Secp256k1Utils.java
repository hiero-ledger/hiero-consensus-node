// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.BC_PROVIDER;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.math.ec.ECPoint;

/**
 * Useful methods for interacting with SECP256K1 ECDSA keys.
 */
public class Secp256k1Utils {
    public static final int ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH = 33;

    private static final int EVM_ADDRESS_BYTE_LENGTH = 20;
    private static final byte ODD_PARITY = (byte) 0x03;
    private static final byte EVEN_PARITY = (byte) 0x02;

    static boolean isValidEvmAddress(@NonNull final ContractID contractId) {
        return contractId.contractNumOrElse(0L) > 0
                || contractId.evmAddressOrElse(Bytes.EMPTY).length() == EVM_ADDRESS_BYTE_LENGTH;
    }

    public static byte[] extractEcdsaPublicKey(final ECPrivateKey key) {
        final ECPoint pointQ =
                ECNamedCurveTable.getParameterSpec("secp256k1").getG().multiply(key.getS());
        return pointQ.getEncoded(true);
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
