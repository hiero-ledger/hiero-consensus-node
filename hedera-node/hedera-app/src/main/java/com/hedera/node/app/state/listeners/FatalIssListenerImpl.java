// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.failure.IssDetectionStagingCoordinator;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.notification.IssNotification;

/**
 * Listener for fatal ISS events (i.e. {@code SELF_ISS} or {@code CATASTROPHIC_ISS}). It logs the event and hands the
 * ISS round to {@link IssDetectionStagingCoordinator}, which captures the ISS-round block and stages it to the node-local
 * {@code issBlockDir} for the deployment's uploader to ship for triage (no-op unless
 * {@code failureBlockStaging.issBlockStagingEnabled} is set). The block stream manager is deliberately allowed to keep
 * processing rounds normally; if the platform later reaches {@code CATASTROPHIC_FAILURE}, the open/pending blocks flushed
 * there are staged separately by {@code TriageBlockStagingCoordinator}.
 */
@Singleton
public class FatalIssListenerImpl implements AsyncFatalIssListener {

    private static final Logger log = LogManager.getLogger(FatalIssListenerImpl.class);

    private final IssDetectionStagingCoordinator detectionStagingCoordinator;

    @Inject
    public FatalIssListenerImpl(@NonNull final IssDetectionStagingCoordinator detectionStagingCoordinator) {
        this.detectionStagingCoordinator = requireNonNull(detectionStagingCoordinator);
    }

    @Override
    public void notify(@NonNull final IssNotification data) {
        log.warn("ISS detected (type={}, round={})", data.getIssType(), data.getRound());
        detectionStagingCoordinator.captureAndStage(data.getIssType(), data.getRound());
    }
}
