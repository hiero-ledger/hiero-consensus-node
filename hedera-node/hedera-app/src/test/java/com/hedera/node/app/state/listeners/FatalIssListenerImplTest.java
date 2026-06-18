// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import static org.mockito.Mockito.verify;

import com.hedera.node.app.blocks.cloud.uploader.IssDetectionUploadCoordinator;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FatalIssListenerImplTest {

    @Mock
    private IssDetectionUploadCoordinator detectionUploadCoordinator;

    @Test
    void delegatesDetectedIssToTheDetectionCoordinator() {
        final var listener = new FatalIssListenerImpl(detectionUploadCoordinator);

        listener.notify(new IssNotification(42L, IssType.SELF_ISS));

        verify(detectionUploadCoordinator).captureAndUpload(IssType.SELF_ISS, 42L);
    }
}
