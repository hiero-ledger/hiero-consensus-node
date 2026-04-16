// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.app.services.benchmark;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.utility.InstantUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.metrics.statistics.AverageAndMax;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.sloth.fixtures.app.SlothService;
import org.hiero.sloth.fixtures.app.state.BenchmarkServiceStateSpecification;
import org.hiero.sloth.fixtures.network.transactions.BenchmarkTransaction;
import org.hiero.sloth.fixtures.network.transactions.SlothTransaction;

/**
 * A service that logs latency for benchmark transactions.
 *
 * <p>This service listens for {@link BenchmarkTransaction}s and logs the latency
 * between when the transaction was submitted (timestamp embedded in the transaction)
 * and when it reached the handle method.
 */
@SuppressWarnings("unused")
public class BenchmarkService implements SlothService {

    private static final Logger log = LogManager.getLogger(BenchmarkService.class);

    private static final String NAME = "BenchmarkService";

    private static final BenchmarkStateSpecification STATE_SPECIFICATION = new BenchmarkStateSpecification();

    /**
     * Log prefix used for benchmark measurements.
     */
    private static final String BENCHMARK_LOG_PREFIX = "BENCHMARK:";

    /** The ID of this node */
    private NodeId selfId;

    /** The metric used to track the latency */
    private AverageAndMax latencyMetric;

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
    public BenchmarkServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }

    @Override
    public void initialize(
            @NonNull final InitTrigger trigger,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final VirtualMapState state) {
        this.selfId = selfId;
        this.latencyMetric = new AverageAndMax(
                metrics,
                "app",
                "slothLatency",
                "the latency of sloth transaction in microseconds",
                FloatFormats.FORMAT_16_2);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final ConsensusEvent event,
            @NonNull final SlothTransaction transaction,
            @NonNull final Instant transactionTimestamp,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {

        if (!transaction.hasBenchmarkTransaction()) {
            return;
        }

        if (!event.getCreatorId().equals(selfId)) {
            // Only log transactions created by this node because the timestamp was based on the local clock
            return;
        }

        // Capture handle time immediately before any other operations
        final Instant handleTime = Instant.now();

        final BenchmarkTransaction benchmarkTx = transaction.getBenchmarkTransaction();
        final Instant submissionTime = InstantUtils.microsToInstant(benchmarkTx.getSubmissionTimeMicros());
        final long latencyMicros = ChronoUnit.MICROS.between(submissionTime, handleTime);

        // Log the measurement data in a parseable format
        // Format: BENCHMARK: nonce=<n>, latency=<l>μs, submissionTime=<s>, handleTime=<h>
        log.info(
                DEMO_INFO.getMarker(),
                "{} nonce={}, latency={}μs, submissionTime={}, handleTime={}",
                BENCHMARK_LOG_PREFIX,
                transaction.getNonce(),
                latencyMicros,
                InstantUtils.instantToMicros(submissionTime),
                InstantUtils.instantToMicros(handleTime));

        if (latencyMetric != null) {
            latencyMetric.update(latencyMicros);
        }
    }
}
