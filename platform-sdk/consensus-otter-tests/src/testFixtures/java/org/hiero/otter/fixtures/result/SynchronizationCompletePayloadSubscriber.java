// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

@FunctionalInterface
public interface SynchronizationCompletePayloadSubscriber {

    SubscriberAction onPayload(@NonNull SynchronizationCompletePayload payload, @Nullable NodeId nodeId);
}
