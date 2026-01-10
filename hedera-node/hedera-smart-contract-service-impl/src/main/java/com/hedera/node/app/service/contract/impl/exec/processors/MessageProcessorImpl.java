// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.OperationTracer;

public interface MessageProcessorImpl {
    void start( MessageFrame frame, OperationTracer tracer );
    void codeSuccess( MessageFrame frame, OperationTracer tracer );
    void revert( MessageFrame frame );
    default void exceptionalHalt1( MessageFrame frame ) { FrameUtils.exceptionalHalt(frame); }
    default HederaSystemContract systemContractsRead( Address key ) { return null; }
}
