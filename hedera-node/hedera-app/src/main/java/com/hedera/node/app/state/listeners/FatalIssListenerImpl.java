// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.cloud.uploader.IssDetectionUploadCoordinator;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.notification.IssNotification;

/**
 * Listener for fatal ISS events (i.e. {@code SELF_ISS} or {@code CATASTROPHIC_ISS}). It logs the event and hands the
 * ISS round to {@link IssDetectionUploadCoordinator}, which captures the ISS-round block and uploads it to the
 * {@code iss/} bucket folder for triage (no-op unless {@code failureBlockUpload.issBlockUploadEnabled} is set). The block
 * stream manager is deliberately allowed to keep processing rounds normally; if the platform later reaches
 * {@code CATASTROPHIC_FAILURE}, the open/pending blocks flushed there are uploaded separately to the {@code triage/}
 * folder by {@code IssBlockUploadCoordinator}.
 */
@Singleton
public class FatalIssListenerImpl implements AsyncFatalIssListener {

    private static final Logger log = LogManager.getLogger(FatalIssListenerImpl.class);

    private final IssDetectionUploadCoordinator detectionUploadCoordinator;

    @Inject
    public FatalIssListenerImpl(@NonNull final IssDetectionUploadCoordinator detectionUploadCoordinator) {
        this.detectionUploadCoordinator = requireNonNull(detectionUploadCoordinator);
    }

    @Override
    public void notify(@NonNull final IssNotification data) {
        log.warn("ISS detected (type={}, round={})", data.getIssType(), data.getRound());
        detectionUploadCoordinator.captureAndUpload(data.getIssType(), data.getRound());
    }
}
