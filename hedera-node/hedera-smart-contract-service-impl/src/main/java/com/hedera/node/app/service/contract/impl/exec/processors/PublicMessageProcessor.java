// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.OperationTracer;

public interface PublicMessageProcessor {
    void start(MessageFrame frame, OperationTracer operationTracer);
    void codeSuccess(MessageFrame frame, OperationTracer operationTracer);
    void revert(MessageFrame frame);
}
