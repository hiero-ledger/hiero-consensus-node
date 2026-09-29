// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.main.model.ConsensusEvent;
import org.hiero.consensus.main.model.EventDescriptorWrapper;
import org.hiero.consensus.main.model.ConsensusTransaction;
import org.hiero.consensus.main.model.Transaction;

public class FakeConsensusEvent extends FakeEvent implements ConsensusEvent {
    private final long consensusOrder;
    private final Instant consensusTimestamp;

    private Hash hash;

    public FakeConsensusEvent(
            @NonNull final FakeEvent event, final long consensusOrder, @NonNull final Instant consensusTimestamp) {
        super(event.getCreatorId(), event.getTimeCreated(), event.transaction, event.getBirthRound());
        this.consensusOrder = consensusOrder;
        this.consensusTimestamp = requireNonNull(consensusTimestamp);
        this.hash = event.getHash();
        event.transaction.setConsensusTimestamp(consensusTimestamp);
    }

    @Override
    @NonNull
    public List<ConsensusTransaction> getTransactions() {
        return List.of(transaction);
    }

    @NonNull
    @Override
    public Hash getHash() {
        return hash;
    }

    @NonNull
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
