// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.platform.system.events.ConsensusEvent;
import com.swirlds.platform.system.transaction.ConsensusTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;

public class FakeConsensusEvent extends FakeEvent implements ConsensusEvent {
    private final long consensusOrder;
    private final Instant consensusTimestamp;

    public FakeConsensusEvent(
            @NonNull final FakeEvent event,
            final long consensusOrder,
            @NonNull final Instant consensusTimestamp,
            @NonNull final SemanticVersion version) {
        super(event.getCreatorId(), event.getTimeCreated(), version, event.transaction);
        this.consensusOrder = consensusOrder;
        this.consensusTimestamp = requireNonNull(consensusTimestamp);
        event.transaction.setConsensusTimestamp(consensusTimestamp);
    }

    @Override
    public @NonNull Iterator<ConsensusTransaction> consensusTransactionIterator() {
        return Collections.singleton((ConsensusTransaction) transaction).iterator();
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
