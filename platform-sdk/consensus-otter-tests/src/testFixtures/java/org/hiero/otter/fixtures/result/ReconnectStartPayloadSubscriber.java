package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.payload.LogPayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

@FunctionalInterface
public interface ReconnectStartPayloadSubscriber {

    SubscriberAction onPayload(@NonNull ReconnectStartPayload payload, @Nullable NodeId nodeId);
}
