// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashingOutputStream;

public class RedactedEventHasher {
    /** The hashing stream for the event. */
    private final MessageDigest eventDigest = DigestType.SHA_384.buildDigest();

    final WritableSequentialData eventStream = new WritableStreamingData(new HashingOutputStream(eventDigest));
    /** The hashing stream for the transactions. */
    private final MessageDigest transactionDigest = DigestType.SHA_384.buildDigest();

    final WritableSequentialData transactionStream =
            new WritableStreamingData(new HashingOutputStream(transactionDigest));

    @NonNull
    public Hash hashEvent(
            @NonNull final EventCore eventCore,
            @NonNull final List<EventDescriptor> parents,
            @NonNull final List<TransactionWrapper> transactions) {}
}
