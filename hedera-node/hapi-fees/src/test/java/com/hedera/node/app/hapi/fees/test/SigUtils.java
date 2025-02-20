// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import java.util.SplittableRandom;

public class SigUtils {
    private static final SplittableRandom r = new SplittableRandom();

    public static final SignatureMap A_SIG_MAP = sigMapOfSize(3);

    public static SignatureMap sigMapOfSize(int n) {
        final var sigMap = SignatureMap.newBuilder();
        while (n-- > 0) {
            sigMap.addSigPair(randSigPair());
        }
        return sigMap.build();
    }

    public static SignaturePair randSigPair() {
        return SignaturePair.newBuilder()
                .setPubKeyPrefix(randomByteString(r.nextInt(3) + 1))
                .setEd25519(randomByteString(64))
                .build();
    }

    public static ByteString randomByteString(final int n) {
        return ByteString.copyFrom(randomBytes(n));
    }

    public static byte[] randomBytes(final int n) {
        final var answer = new byte[n];
        r.nextBytes(answer);
        return answer;
    }
}
