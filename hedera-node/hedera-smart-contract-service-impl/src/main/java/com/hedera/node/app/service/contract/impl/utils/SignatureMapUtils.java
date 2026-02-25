// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.pbj.runtime.OneOf;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Some utility methods that are useful for processing system contracts.
 */
public class SignatureMapUtils {
    private SignatureMapUtils() {
        // Utility class
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * When a signature map is passed to a system contract function and contains ECDSA signatures, before the signatures
     * can be verified with a {@link SignatureVerifier}, they must be preprocessed in the following way:
     * (1) If the v value is greater than 35, it must be checked to see if it matches the chain ID per EIP 155
     * (2) Strip the v value from the public key as it is not needed for verification
     * @param sigMap
     * @return a new SignatureMap with the ECDSA signatures preprocessed
     */
    public static SignatureMap preprocessEcdsaSignatures(@NonNull final SignatureMap sigMap, final int chainId) {
        final List<SignaturePair> newPairs = new ArrayList<>();
        for (var spair : sigMap.sigPair()) {
            if (spair.hasEcdsaSecp256k1()) {
                final var ecSig = requireNonNull(spair.ecdsaSecp256k1());
                if (ecSig.length() > 64) {
                    if (!validChainId(ecSig.toByteArray(), chainId)) {
                        throw new IllegalArgumentException("v value in ECDSA signature does not match chain ID");
                    }
                    spair = new SignaturePair(
                            spair.pubKeyPrefix(), new OneOf<>(SignatureOneOfType.ECDSA_SECP256K1, ecSig.slice(0, 64)));
                }
            }
            newPairs.add(spair);
        }
        return new SignatureMap(newPairs);
    }

    /**
     * Check that the v value in an ECDSA signature matches the chain ID if it is greater than 35 per EIP 155
     * @param ecSig (must be >64 bytes)
     * @param chainId chainId which is to match that encoded in `ecSig`
     * @return true if the v value matches the chain ID or is not relevant, false otherwise
     * <p>
     * "Not relevant" means the signature is not EIP-155 encoded, so that the `v` value is simply
     * the parity bit.
     * <p>
     * There's discussion on actually how big the chainid can get.  Some argue for 256 bits, or
     * maybe just a little less than 255 bits.  Others argue for different numbers.  `long` is
     * generous now for everything at chainlist.org (though of course someone could get silly later).
     *
     */
    public static boolean validChainId(final byte[] ecSig, final long chainId) {
        if (ecSig.length <= 64) throw new IllegalArgumentException("signature needs to be at least 65 bytes");
        try {
            final long v = new BigInteger(+1, ecSig, 64, ecSig.length - 64).longValueExact();
            if (v >= 35) {
                // See EIP 155 - https://eips.ethereum.org/EIPS/eip-155
                final var chainIdParityZero = 35 + (chainId * 2L);
                return v == chainIdParityZero || v == chainIdParityZero + 1;
            }
            return true;
        } catch (final ArithmeticException e) {
            throw new IllegalArgumentException("EIP-155 encoded chain id too large (longer than long)");
        }
    }

    /**
     * The Ethereum world uses 65+ byte EC signatures, our cryptography library uses 64 byte EC signatures.  The
     * difference is the addition of an extra "parity" field at the end of the 64 byte signature (used so that
     * `ECRECOVER` can recover the public key (== Ethereum address) from the signature.  And, the chain id can
     * be encoded in that field (per EIP-155) and if the chain id is large enough (like Hedera mainnet/testnet
     * chain ids) that last field can be more than one byte.
     * <p>
     * This method is a shim for that mismatch. It strips the extra bytes off any 65+ byte EC signatures it finds.
     *
     * @param sigMap Signature map from user - possibly contains 65+ byte EC signatures
     * @return Signature map with only 64 byte EC signatures (and all else unchanged)
     */
    public static @NonNull SignatureMap stripRecoveryIdFromEcdsaSignatures(@NonNull final SignatureMap sigMap) {
        final List<SignaturePair> newPairs = new ArrayList<>();
        for (var spair : sigMap.sigPair()) {
            if (spair.hasEcdsaSecp256k1()) {
                final var ecSig = requireNonNull(spair.ecdsaSecp256k1());
                if (ecSig.length() > 64) {
                    spair = new SignaturePair(
                            spair.pubKeyPrefix(),
                            new OneOf<>(SignaturePair.SignatureOneOfType.ECDSA_SECP256K1, ecSig.slice(0, 64)));
                }
            }
            newPairs.add(spair);
        }
        return new SignatureMap(newPairs);
    }
}
