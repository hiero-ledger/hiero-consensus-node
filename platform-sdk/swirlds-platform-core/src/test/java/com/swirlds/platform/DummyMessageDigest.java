// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import java.security.MessageDigest;
import java.util.Arrays;
import org.hiero.consensus.model.crypto.DigestType;

public class DummyMessageDigest extends MessageDigest {
    private static final byte mdHash[] = new byte[DigestType.SHA_384.digestLength()];

    static {
        Arrays.fill(mdHash, (byte) 1);
    }

    public DummyMessageDigest() {
        super(DigestType.SHA_384.algorithmName());
    }

    public static byte[] getDummyHashValue() {
        return mdHash.clone();
    }

    @Override
    public void update(byte arg0) {}

    @Override
    public void update(byte[] arg0) {}

    @Override
    public byte[] digest() {
        return mdHash.clone();
    }

    @Override
    public byte[] digest(byte[] arg0) {
        return mdHash.clone();
    }

    @Override
    protected void engineUpdate(byte arg0) {}

    @Override
    protected void engineUpdate(byte[] arg0, int arg1, int arg2) {}

    @Override
    protected byte[] engineDigest() {
        return null;
    }

    @Override
    protected void engineReset() {}
}
