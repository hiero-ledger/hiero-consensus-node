// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.OperationTracer;

// spotless:off
public interface PublicMessageProcessor {
    void start      (MessageFrame f, OperationTracer t);
    void codeSuccess(MessageFrame f, OperationTracer t);
    void revert     (MessageFrame f);
}
// spotless:on
