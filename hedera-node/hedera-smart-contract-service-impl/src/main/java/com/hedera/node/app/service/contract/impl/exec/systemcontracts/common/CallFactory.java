// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Common interface for factories that create descendents of {@link AbstractCallAttempt}.
 * @param <T> the type of the call
 */
public interface CallFactory<T extends AbstractCallAttempt<T>> {
    /**
     * @param contractID the id of the system contract we are calling
     * @param input the input to the contract
     * @param callType the call type of the current frame
     * @param frame the message frame
     * @return the call
     */
    @NonNull
    T createCallAttemptFrom(
            @NonNull ContractID contractID,
            @NonNull Bytes input,
            @NonNull FrameUtils.CallType callType,
            @NonNull MessageFrame frame);
}
