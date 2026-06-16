// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.notification.IssNotification;

/**
 * Listener for fatal ISS events (i.e. {@code SELF_ISS} or {@code CATASTROPHIC_ISS}). It only records the event in the
 * log; the block stream manager is deliberately allowed to keep processing rounds normally. The contents of any open
 * blocks are flushed to disk for triage later, when the platform reaches {@code CATASTROPHIC_FAILURE} (see
 * {@link com.hedera.node.app.blocks.BlockStreamManager#awaitFatalShutdown}), after which the flushed block(s) are
 * uploaded to a bucket by {@code IssBlockUploadCoordinator}.
 */
@Singleton
public class FatalIssListenerImpl implements AsyncFatalIssListener {

    private static final Logger log = LogManager.getLogger(FatalIssListenerImpl.class);

    @Inject
    public FatalIssListenerImpl() {
        // No dependencies; this listener only logs ISS events.
    }

    @Override
    public void notify(@NonNull final IssNotification data) {
        log.warn("ISS detected (type={}, round={})", data.getIssType(), data.getRound());
    }
}
