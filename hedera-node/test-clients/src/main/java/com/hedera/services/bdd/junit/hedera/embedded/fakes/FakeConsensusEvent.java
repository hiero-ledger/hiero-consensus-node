// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.jetbrains.annotations.NotNull;

public class FakeConsensusEvent extends FakeEvent implements ConsensusEvent {
    private final long consensusOrder;
    private final Instant consensusTimestamp;

    private Hash hash;

    public FakeConsensusEvent(
            @NonNull final FakeEvent event,
            final long consensusOrder,
            @NonNull final Instant consensusTimestamp,
            @NonNull final SemanticVersion version) {
        super(event.getCreatorId(), event.getTimeCreated(), version, event.transaction);
        this.consensusOrder = consensusOrder;
        this.consensusTimestamp = requireNonNull(consensusTimestamp);
        this.hash = event.getHash();
        event.transaction.setConsensusTimestamp(consensusTimestamp);
    }

    @Override
    public @NonNull Iterator<ConsensusTransaction> consensusTransactionIterator() {
        return Collections.singleton((ConsensusTransaction) transaction).iterator();
    }

    @NotNull
    @Override
    public Hash getHash() {
        return hash;
    }

    @NotNull
    @Override
    public Iterator<EventDescriptorWrapper> allParentsIterator() {
        return Collections.emptyIterator();
    }

    @Override
    public long getConsensusOrder() {
        return consensusOrder;
    }

    @Override
    public Instant getConsensusTimestamp() {
        return consensusTimestamp;
    }
}
