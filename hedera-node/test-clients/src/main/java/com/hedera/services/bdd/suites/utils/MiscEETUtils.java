// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class MiscEETUtils {
    @SuppressWarnings("java:S2245") // using java.util.Random in tests is fine
    private static final Random r = new Random(298238L);

    private MiscEETUtils() {}

    public static byte[] genRandomBytes(int numBytes) {
        byte[] contents = new byte[numBytes];
        r.nextBytes(contents);
        return contents;
    }

    public static List<ByteString> batchOfSize(int size) {
        var batch = new ArrayList<ByteString>();
        for (int i = 0; i < size; i++) {
            batch.add(metadata("memo" + i));
        }
        return batch;
    }

    public static ByteString metadataOfLength(int length) {
        return ByteString.copyFrom(genRandomBytes(length));
    }

    public static ByteString metadata(String contents) {
        return ByteString.copyFromUtf8(contents);
    }
}
