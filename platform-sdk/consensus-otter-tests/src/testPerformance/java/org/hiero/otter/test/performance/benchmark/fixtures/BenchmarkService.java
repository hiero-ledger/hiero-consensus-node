// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.benchmark.fixtures;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.otter.fixtures.app.OtterService;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;
import org.hiero.otter.fixtures.network.transactions.BenchmarkTransaction;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;

/**
 * A service that logs latency for benchmark transactions.
 *
 * <p>This service listens for {@link BenchmarkTransaction}s and logs the latency
 * between when the transaction was submitted (timestamp embedded in the transaction)
 * and when it reached consensus (the consensus timestamp provided by the platform).
 */
public class BenchmarkService implements OtterService {

    private static final Logger log = LogManager.getLogger(BenchmarkService.class);

    private static final String NAME = "BenchmarkService";

    private static final BenchmarkStateSpecification STATE_SPECIFICATION = new BenchmarkStateSpecification();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OtterServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final ConsensusEvent event,
            @NonNull final OtterTransaction transaction,
            @NonNull final Instant transactionTimestamp,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {

        if (transaction.hasBenchmarkTransaction()) {
            final BenchmarkTransaction benchmarkTx = transaction.getBenchmarkTransaction();
            final long submissionTimeMillis = benchmarkTx.getSubmissionTimeMillis();
            final long consensusTimeMillis = transactionTimestamp.toEpochMilli();
            final long latencyMillis = consensusTimeMillis - submissionTimeMillis;

            log.info(
                    DEMO_INFO.getMarker(),
                    "BENCHMARK BenchmarkService: nonce={}, latency={}ms, submissionTime={}, consensusTime={}",
                    transaction.getNonce(),
                    latencyMillis,
                    submissionTimeMillis,
                    consensusTimeMillis);
        }
    }
}
