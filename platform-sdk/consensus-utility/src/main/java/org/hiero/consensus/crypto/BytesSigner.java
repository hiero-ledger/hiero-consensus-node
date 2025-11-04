package org.hiero.consensus.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

@FunctionalInterface
public interface BytesSigner {
    Bytes sign(@NonNull Bytes data);
}
