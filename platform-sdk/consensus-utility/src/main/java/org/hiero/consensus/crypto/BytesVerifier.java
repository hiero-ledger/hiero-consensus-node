package org.hiero.consensus.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;

public interface BytesVerifier {
    boolean verify(Bytes signature, Bytes data);
}
