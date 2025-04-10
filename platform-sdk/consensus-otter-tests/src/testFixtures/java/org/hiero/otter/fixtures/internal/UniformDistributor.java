// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;

/**
 * A simple implementation of the {@link Distributor} interface that distributes transactions uniformly.
 */
public class UniformDistributor implements Distributor {

    private final Network network;
    private final Randotron randotron;

    /**
     * Constructor for the {@link UniformDistributor} class.
     *
     * @param network   the network to distribute transactions to
     * @param randotron the random number generator to use
     */
    public UniformDistributor(@NonNull final Network network, @NonNull final Randotron randotron) {
        this.network = requireNonNull(network);
        this.randotron = requireNonNull(randotron);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction() {
        final int n = network.getNodes().size();
        if (n == 0) {
            return;
        }
        final int index = randotron.nextInt(n);
        final Node node = network.getNodes().get(index);
        node.submitTransaction(randotron.nextByteArray(32));
    }
}
