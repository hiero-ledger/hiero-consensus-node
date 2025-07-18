package org.hiero.otter.fixtures.internal.result;

import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.RECONNECT_COMPLETE;

/**
 * Implementation of the {@link SingleNodeReconnectResult} interface.
 */
public class SingleNodeReconnectResultImpl implements SingleNodeReconnectResult {

    private final SingleNodePlatformStatusResults statusResults;
    private final SingleNodeLogResult logResults;

    /**
     * Constructor for SingleNodeReconnectResultImpl.
     * @param statusResults the platform status results for the single node
     * @param logResults the log results for the single node
     */
    public SingleNodeReconnectResultImpl(@NonNull final SingleNodePlatformStatusResults statusResults,
            @NonNull final SingleNodeLogResult logResults) {
        this.statusResults = requireNonNull(statusResults, "statusResults must not be null");
        this.logResults = requireNonNull(logResults, "logResults must not be null");
    }


    @Override
    public int numSuccessfulReconnects() {
        return (int) statusResults.statusProgression().stream().filter(RECONNECT_COMPLETE::equals).count();
    }

    @Override
    public int numFailedReconnects() {
        return (int) logResults.logs().stream().filter(log -> log.message().contains(ReconnectFailurePayload.class.toString())).count();
    }

    @Override
    public void clear() {
        statusResults.clear();
        logResults.clear();
    }
}
