// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;

public class FakeEvent implements Event {
    private static final Bytes FAKE_SHA_384_SIGNATURE = Bytes.wrap(new byte[] {
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08,
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08,
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08,
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08,
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08,
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08,
    });

    private final NodeId creatorId;
    private final Instant timeCreated;
    private final SemanticVersion version;
    private final EventCore eventCore;
    public final TransactionWrapper transaction;

    public FakeEvent(
            @NonNull final NodeId creatorId,
            @NonNull final Instant timeCreated,
            @NonNull final SemanticVersion version,
            @NonNull final TransactionWrapper transaction) {
        this.version = requireNonNull(version);
        this.creatorId = requireNonNull(creatorId);
        this.timeCreated = requireNonNull(timeCreated);
        this.transaction = requireNonNull(transaction);
        this.eventCore = EventCore.newBuilder()
                .creatorNodeId(creatorId.id())
                .timeCreated(HapiUtils.asTimestamp(timeCreated))
                .version(version)
                .build();
    }

    @Override
    public Iterator<Transaction> transactionIterator() {
        return Collections.singleton((Transaction) transaction).iterator();
    }

    @Override
    public Instant getTimeCreated() {
        return timeCreated;
    }

    @NonNull
    @Override
    public NodeId getCreatorId() {
        return creatorId;
    }

    @NonNull
    @Override
    public SemanticVersion getSoftwareVersion() {
        return version;
    }

    @NonNull
    @Override
    public EventCore getEventCore() {
        return eventCore;
    }

    @NonNull
    @Override
    public Bytes getSignature() {
        return FAKE_SHA_384_SIGNATURE;
    }

    @NonNull
    public HederaFunctionality function() {
        return TransactionParts.from(transaction.getApplicationTransaction()).function();
    }

    @NonNull
    public Hash getHash() {
        return new Hash();
    }
}
