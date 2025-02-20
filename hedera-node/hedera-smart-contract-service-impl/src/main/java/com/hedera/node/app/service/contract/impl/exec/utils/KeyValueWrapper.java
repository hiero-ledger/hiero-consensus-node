// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public final class KeyValueWrapper {

    public enum KeyType {
        INVALID_KEY,
        INHERIT_ACCOUNT_KEY,
        CONTRACT_ID,
        DELEGATABLE_CONTRACT_ID,
        ED25519,
        ECDSA_SECP256K1
    }

    /* ---  Only 1 of these values should be set when the input is valid. --- */
    private final boolean shouldInheritAccountKey;
    private final ContractID contractID;
    private final byte[] ed25519;
    private final byte[] ecdsaSecp256k1;
    private final ContractID delegatableContractID;
    private final KeyType keyType;

    private static final int ED25519_BYTE_LENGTH = 32;
    private static final int ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH = 33;

    /* --- This field is populated only when `shouldInheritAccountKey` is true --- */
    private Key inheritedKey;

    public KeyValueWrapper(
            final boolean shouldInheritAccountKey,
            @Nullable final ContractID contractID,
            @NonNull final byte[] ed25519,
            @NonNull final byte[] ecdsaSecp256k1,
            @Nullable final ContractID delegatableContractID) {
        this.shouldInheritAccountKey = shouldInheritAccountKey;
        this.contractID = contractID;
        this.ed25519 = requireNonNull(ed25519);
        this.ecdsaSecp256k1 = requireNonNull(ecdsaSecp256k1);
        this.delegatableContractID = delegatableContractID;
        this.keyType = isValidConstruction() ? this.computeKeyType() : KeyType.INVALID_KEY;
    }

    private boolean isContractIDSet() {
        return contractID != null;
    }

    private boolean isDelegatableContractIdSet() {
        return delegatableContractID != null;
    }

    public boolean isShouldInheritAccountKeySet() {
        return shouldInheritAccountKey;
    }

    private boolean isEd25519KeySet() {
        return ed25519.length == ED25519_BYTE_LENGTH;
    }

    private boolean isEcdsaSecp256k1KeySet() {
        return ecdsaSecp256k1.length == ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH;
    }

    public void setInheritedKey(final Key key) {
        this.inheritedKey = key;
    }

    private boolean isValidConstruction() {
        var keyCount = 0;
        if (isContractIDSet()) keyCount++;
        if (isDelegatableContractIdSet()) keyCount++;
        if (isShouldInheritAccountKeySet()) keyCount++;
        if (isEd25519KeySet()) keyCount++;
        if (isEcdsaSecp256k1KeySet()) keyCount++;
        return keyCount == 1;
    }

    private KeyType computeKeyType() {
        if (isShouldInheritAccountKeySet()) {
            return KeyType.INHERIT_ACCOUNT_KEY;
        } else if (isContractIDSet()) {
            return KeyType.CONTRACT_ID;
        } else if (isEd25519KeySet()) {
            return KeyType.ED25519;
        } else if (isEcdsaSecp256k1KeySet()) {
            return KeyType.ECDSA_SECP256K1;
        } else {
            return KeyType.DELEGATABLE_CONTRACT_ID;
        }
    }

    public Key asGrpc() {
        return switch (keyType) {
            case INVALID_KEY -> Key.DEFAULT;
            case INHERIT_ACCOUNT_KEY -> this.inheritedKey;
            case CONTRACT_ID -> Key.newBuilder().contractID(contractID).build();
            case ED25519 -> Key.newBuilder().ed25519(Bytes.wrap(ed25519)).build();
            case ECDSA_SECP256K1 -> Key.newBuilder()
                    .ecdsaSecp256k1(Bytes.wrap(ecdsaSecp256k1))
                    .build();
            case DELEGATABLE_CONTRACT_ID -> Key.newBuilder()
                    .delegatableContractId(delegatableContractID)
                    .build();
        };
    }
}
