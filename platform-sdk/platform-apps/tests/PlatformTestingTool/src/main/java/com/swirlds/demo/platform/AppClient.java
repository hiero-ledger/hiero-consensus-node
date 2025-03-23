// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

/*
 * Simulate Hedera Client to access file or map without going through
 * consensus
 */
public class AppClient extends Thread {

    Platform platform;
    NodeId selfId;
    String myName;
    byte[] submittedBytes = null;
    PayloadCfgSimple config;
    /** generate different payload bytes according to config */
    private PttTransactionPool pttTransactionPool;

    private TransactionSubmitter submitter;
    private ExpectedFCMFamily expectedFCMFamily;

    AppClient(
            @NonNull final Platform platform,
            @NonNull final NodeId selfId,
            @NonNull final SuperConfig currentConfig,
            @NonNull final String myName,
            @NonNull final PlatformTestingToolConsensusStateEventHandler stateEventHandler) {
        this.platform = Objects.requireNonNull(platform, "platform must not be null");
        this.selfId = Objects.requireNonNull(selfId, "selfId must not be null");
        Objects.requireNonNull(currentConfig, "currentConfig must not be null");
        this.myName = Objects.requireNonNull(myName, "myName must not be null");

        PayloadCfgSimple pConfig = currentConfig.getPayloadConfig();

        PayloadConfig payloadConfig = PayloadConfig.builder()
                .setAppendSig(pConfig.isAppendSig())
                .setInsertSeq(pConfig.isInsertSeq())
                .setVariedSize(pConfig.isVariedSize())
                .setPayloadByteSize(pConfig.getPayloadByteSize())
                .setMaxByteSize(pConfig.getMaxByteSize())
                .setType(pConfig.getType())
                .setDistribution(pConfig.getDistribution())
                .build();

        SubmitConfig submitConfig = currentConfig.getSubmitConfig();

        try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
            final PlatformTestingToolState state = wrapper.get();
            submitter = new TransactionSubmitter(submitConfig, stateEventHandler.getControlQuorum());
            expectedFCMFamily = state.getStateExpectedMap();
        }

        pttTransactionPool = new PttTransactionPool(
                platform,
                platform.getSelfId().id(),
                payloadConfig,
                myName,
                currentConfig.getFcmConfig(),
                currentConfig.getVirtualMerkleConfig(),
                currentConfig.getFreezeConfig(),
                currentConfig.getTransactionPoolConfig(),
                submitter,
                expectedFCMFamily);

        config = pConfig;
    }

    @Override
    public void run() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        } // wait a while to let enough file or fcm available
    }
}
