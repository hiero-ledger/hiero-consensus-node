// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.time.TimeTickReceiver;
import org.hiero.otter.fixtures.generator.TransactionSubmitter;

/**
 * An implementation of {@link Network} that is based on the Turtle framework.
 */
public class TurtleNetwork implements Network, TimeTickReceiver, TransactionSubmitter {

    private static final Logger log = Loggers.getLogger(TurtleNetwork.class);

    private final AddressBook addressBook;
    private final ExecutorService threadPool;

    public TurtleNetwork(
            @NonNull final Randotron randotron,
            @NonNull final Duration averageNetworkDelay,
            @NonNull final Duration standardDeviationNetworkDelay) {

        this.addressBook = new AddressBook();
        addressBook.setRound(randotron.nextPositiveLong(Long.MAX_VALUE / 2));

        this.threadPool = Executors.newCachedThreadPool();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> addNodes(final int count) {
        log.warn("Adding nodes is not implemented yet.");
        return List.of();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(@NonNull final Duration timeout) {
        log.warn("Starting the network is not implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InstrumentedNode addInstrumentedNode() {
        log.warn("Adding instrumented nodes is not implemented yet.");
        return new TurtleInstrumentedNode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        // Not implemented. Logs no warning as this method is called with high frequency.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final NodeId nodeId, @NonNull final Bytes payload) {
        log.warn("Submitting transaction is not implemented yet.");
    }
}
