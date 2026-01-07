// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.OperationTracer;

public interface MessageProcessorImpl {
    void start( MessageFrame frame, OperationTracer tracer );
    void codeSuccess( MessageFrame frame, OperationTracer tracer );
    void revert( MessageFrame frame );
    default HederaSystemContract systemContractsRead( Address key ) { return null; }
}
