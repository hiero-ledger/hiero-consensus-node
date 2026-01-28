// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.extendable;

import static org.hiero.consensus.io.extendable.ExtendableInputStream.extendInputStream;
import static org.hiero.consensus.io.extendable.ExtendableOutputStream.extendOutputStream;

import com.swirlds.common.test.fixtures.io.extendable.StreamSanityChecks;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.io.extendable.extensions.CountingStreamExtension;
import org.hiero.consensus.io.extendable.extensions.ExpiringStreamExtension;
import org.hiero.consensus.io.extendable.extensions.HashingStreamExtension;
import org.hiero.consensus.io.extendable.extensions.MaxSizeStreamExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Multi-Extension Tests")
class MultiExtensionTests {

    @Test
    @DisplayName("Input Stream Sanity Test")
    void inputStreamSanityTest() throws IOException {
        StreamSanityChecks.inputStreamSanityCheck((final InputStream base) -> extendInputStream(
                base,
                new CountingStreamExtension(),
                new ExpiringStreamExtension(Duration.ofHours(1)),
                new HashingStreamExtension(DigestType.SHA_384),
                new MaxSizeStreamExtension(Long.MAX_VALUE) /*,
						new ThrottleStreamExtension(Long.MAX_VALUE),
						new TimeoutStreamExtension(Duration.ofHours(1))FUTURE WORK*/));
    }

    @Test
    @DisplayName("Output Stream Sanity Test")
    void outputStreamSanityTest() throws IOException {
        StreamSanityChecks.outputStreamSanityCheck((final OutputStream base) -> extendOutputStream(
                base,
                new CountingStreamExtension(),
                new ExpiringStreamExtension(Duration.ofHours(1)),
                new HashingStreamExtension(DigestType.SHA_384),
                new MaxSizeStreamExtension(Long.MAX_VALUE) /*,
						new ThrottleStreamExtension(Long.MAX_VALUE),
						new TimeoutStreamExtension(Duration.ofHours(1))FUTURE WORK*/));
    }
}
