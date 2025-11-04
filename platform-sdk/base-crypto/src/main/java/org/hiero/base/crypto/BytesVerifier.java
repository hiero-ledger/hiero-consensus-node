package org.hiero.base.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;

public interface BytesVerifier {
    boolean verify(Bytes signature, Bytes data);
}
