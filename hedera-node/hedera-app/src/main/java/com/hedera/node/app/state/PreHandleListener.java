// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.Event;

/** Listener invoked whenever an event is ready for pre-handle */
@FunctionalInterface
public interface PreHandleListener {
    void onPreHandle(@NonNull Event event, @NonNull State state);
}
