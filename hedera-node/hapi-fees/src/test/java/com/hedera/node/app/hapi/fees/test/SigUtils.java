// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.SplittableRandom;

public class SigUtils {
    private static final SplittableRandom r = new SplittableRandom();

    public static final SignatureMap A_SIG_MAP = sigMapOfSize(3);

    public static SignatureMap sigMapOfSize(int n) {
        final var sigMap = SignatureMap.newBuilder();
        while (n-- > 0) {
            sigMap.sigPair(randSigPair());
        }
        return sigMap.build();
    }

    public static SignaturePair randSigPair() {
        return SignaturePair.newBuilder()
                .pubKeyPrefix(randomByteString(r.nextInt(3) + 1))
                .ed25519(randomByteString(64))
                .build();
    }

    public static Bytes randomByteString(final int n) {
        return Bytes.wrap(randomBytes(n));
    }

    public static byte[] randomBytes(final int n) {
        final var answer = new byte[n];
        r.nextBytes(answer);
        return answer;
    }
}
