// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.isDelegateCall;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * This utility class performs checks on the parent of the frame (the initial caller)
 */
@Singleton
public class CallAddressChecks {
    /**
     * Default constructor for injection.
     */
    @Inject
    public CallAddressChecks() {
        // Dagger2
    }

    /**
     * Checks if the given frame's parent is a delegate call.
     *
     * @param frame the frame to check
     * @return true if the frame's parent is a delegate call
     */
    public boolean hasParentDelegateCall(@NonNull final MessageFrame frame) {
        return frame.getMessageFrameStack().size() > 1 && isDelegateCall(parentOf(frame));
    }

    private MessageFrame parentOf(@NonNull final MessageFrame frame) {
        final var iter = frame.getMessageFrameStack().iterator();
        iter.next();
        return iter.next();
    }
}
