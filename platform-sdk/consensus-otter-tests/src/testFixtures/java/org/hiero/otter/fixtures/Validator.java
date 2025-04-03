package org.hiero.otter.fixtures;

import com.swirlds.logging.legacy.LogMarker;

public interface Validator {

    Validator assertLogErrors(LogErrorConfig... configs);

    Validator assertStdOut();

    Validator eventStream(EventStreamConfig... configs);

    Validator reconnectEventStream(Node node);

    Validator validateRemaining(Profile profile);

    Validator consensusRatio(RatioConfig... configs);

    Validator staleRatio(RatioConfig... configs);

    interface LogErrorConfig {
        static LogErrorConfig ignoreMarkers(LogMarker... markers) {
            return null;
        }
    }

    interface EventStreamConfig {
        static EventStreamConfig ignoreNode(Node... nodes) {
            return null;
        }
    }

    interface RatioConfig {
        static RatioConfig within(double min, double max) {
            return null;
        }
    }

    enum Profile {
        DEFAULT, HASHGRAPH
    }
}
