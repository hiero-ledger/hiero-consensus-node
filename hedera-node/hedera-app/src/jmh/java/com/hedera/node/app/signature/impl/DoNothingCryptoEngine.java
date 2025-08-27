// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SerializableHashable;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.TransactionSignature;
import org.hiero.base.io.SelfSerializable;

public class DoNothingCryptoEngine implements Cryptography {

    @NonNull
    @Override
    public Hash digestSync(@NonNull byte[] bytes) {
        return null;
    }

    @Override
    public byte[] digestBytesSync(final @NonNull SelfSerializable serializable) {
        return null;
    }

    @Override
    public Hash digestSync(@NonNull SelfSerializable selfSerializable) {
        return null;
    }

    @Override
    public Hash digestSync(@NonNull SerializableHashable serializableHashable, boolean b) {
        return null;
    }

    @NonNull
    @Override
    public byte[] digestBytesSync(@NonNull final byte[] message) {
        return null;
    }

    @Override
    public boolean verifySync(@NonNull TransactionSignature transactionSignature) {
        return false;
    }

    @Override
    public boolean verifySync(@NonNull List<TransactionSignature> list) {
        return false;
    }

    @Override
    public boolean verifySync(
            @NonNull byte[] bytes,
            @NonNull byte[] bytes1,
            @NonNull byte[] bytes2,
            @NonNull SignatureType signatureType) {
        return false;
    }

    @NonNull
    @Override
    public Hash calcRunningHash(@NonNull Hash hash, @NonNull Hash hash1) {
        return null;
    }
}
