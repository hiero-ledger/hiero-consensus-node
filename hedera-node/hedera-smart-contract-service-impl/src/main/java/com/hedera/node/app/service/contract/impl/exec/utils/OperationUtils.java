// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;

public class OperationUtils {
    public static boolean isDeficientGas(@NonNull final MessageFrame frame, @NonNull final long cost) {
        return frame.getRemainingGas() < cost;
    }
}
