// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.OperationTracer;

public interface MessageProcessorImpl {
    public void start      ( MessageFrame frame, OperationTracer tracer);
    public void codeSuccess( MessageFrame frame, OperationTracer tracer);
    public void revert     ( MessageFrame frame );

}
