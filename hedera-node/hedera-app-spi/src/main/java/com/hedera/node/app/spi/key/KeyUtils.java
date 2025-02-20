// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.key;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Key.KeyOneOfType;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Utility class for working with keys. This validates if the key is empty and valid.
 */
// NOTE: This class is not in the right place. But is needed by several modules.
// !!!!!!!!!!🔥🔥🔥 It should be moved once we find where to keep it. 🔥🔥🔥!!!!!!!!!!!
public class KeyUtils {

    public static final Key IMMUTABILITY_SENTINEL_KEY =
            Key.newBuilder().keyList(KeyList.DEFAULT).build();

    public static final int EVM_ADDRESS_BYTE_LENGTH = 20;
    public static final int ED25519_BYTE_LENGTH = 32;
    private static final byte ODD_PARITY = (byte) 0x03;
    private static final byte EVEN_PARITY = (byte) 0x02;
    public static final int ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH = 33;

    private KeyUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Checks if the given key is empty.
     * For a KeyList type checks if the list is empty.
     * For a ThresholdKey type checks if the list is empty.
     * For an Ed25519 or EcdsaSecp256k1 type checks if there are zero bytes.
     * @param pbjKey the key to check
     * @return true if the key is empty, false otherwise
     */
    public static boolean isEmpty(@Nullable final Key pbjKey) {
        return isEmptyInternal(pbjKey, false);
    }

    /**
     * Checks if the given key is empty.
     * For a KeyList type checks if the list is empty.
     * For a ThresholdKey type checks if the list is empty.
     * For an Ed25519 or EcdsaSecp256k1 type checks if there are zero bytes.
     * @param pbjKey the key to check
     * @param honorImmutable if true, the key is NOT considered EMPTY if it is the IMMUTABILITY_SENTINEL_KEY
     * @return true if the key is empty, false otherwise
     */
    private static boolean isEmptyInternal(@Nullable final Key pbjKey, boolean honorImmutable) {
        if (pbjKey == null) {
            return true;
        }
        final var key = pbjKey.key();
        if (key == null || KeyOneOfType.UNSET.equals(key.kind())) {
            return true;
        }
        if (honorImmutable && IMMUTABILITY_SENTINEL_KEY.equals(pbjKey)) {
            return false;
        }
        if (pbjKey.hasKeyList()) {
            final var keyList = (KeyList) key.value();
            if (keyList.keys().isEmpty()) {
                return true;
            }
            for (final var k : keyList.keys()) {
                if (!isEmpty(k)) {
                    return false;
                }
            }
            return true;
        } else if (pbjKey.hasThresholdKey()) {
            final var thresholdKey = (ThresholdKey) key.value();
            if ((!thresholdKey.hasKeys() || thresholdKey.keys().keys().size() == 0)) {
                return true;
            }
            for (final var k : thresholdKey.keys().keys()) {
                if (!isEmpty(k)) {
                    return false;
                }
            }
            return true;
        } else if (pbjKey.hasEd25519()) {
            return ((Bytes) key.value()).length() == 0;
        } else if (pbjKey.hasEcdsaSecp256k1()) {
            return ((Bytes) key.value()).length() == 0;
        } else if (pbjKey.hasDelegatableContractId() || pbjKey.hasContractID()) {
            return ((ContractID) key.value()).contractNumOrElse(0L) == 0
                    && ((ContractID) key.value()).evmAddressOrElse(Bytes.EMPTY).length() == 0L;
        }
        // ECDSA_384 and RSA_3072 are not supported yet
        return true;
    }

    /**
     * Checks if the given key is valid. Based on the key type it checks the basic requirements
     * for the key type.
     * @param pbjKey the key to check
     * @return true if the key is valid, false otherwise
     */
    public static boolean isValid(@Nullable final Key pbjKey) {
        if (isEmpty(pbjKey)) {
            return false;
        }
        final var key = pbjKey.key();
        if (pbjKey.hasKeyList()) {
            for (Key keys : ((KeyList) key.value()).keys()) {
                if (!isValid(keys)) {
                    return false;
                }
            }
            return true;
        } else if (pbjKey.hasThresholdKey()) {
            final int length = ((ThresholdKey) key.value()).keys().keys().size();
            final int threshold = ((ThresholdKey) key.value()).threshold();
            boolean isKeyListValid = true;
            for (Key keys : ((ThresholdKey) key.value()).keys().keys()) {
                if (!isValid(keys)) {
                    isKeyListValid = false;
                    break;
                }
            }
            return (threshold >= 1 && threshold <= length && isKeyListValid);
        } else if (pbjKey.hasEd25519()) {
            return ((Bytes) key.value()).length() == ED25519_BYTE_LENGTH;
        } else if (pbjKey.hasEcdsaSecp256k1()) {
            final var ecKey = ((Bytes) key.value());
            return ecKey.length() == ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH
                    && (ecKey.getByte(0) == EVEN_PARITY || ecKey.getByte(0) == ODD_PARITY);
        } else if (pbjKey.hasDelegatableContractId() || pbjKey.hasContractID()) {
            return ((ContractID) key.value()).contractNumOrElse(0L) > 0
                    || ((ContractID) key.value()).evmAddressOrElse(Bytes.EMPTY).length() == EVM_ADDRESS_BYTE_LENGTH;
        }
        // ECDSA_384 and RSA_3072 are not supported yet
        return true;
    }
}
