// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.failure;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;

/**
 * An {@link ExceptionalHaltReason} that preserves the status of a
 * {@link com.hedera.node.app.spi.workflows.HandleException} thrown from inside EVM execution
 * (e.g., re-thrown by a system contract), so the failure can be resolved through the normal
 * frame lifecycle instead of escaping it as a Java exception.
 */
public record HandleExceptionHaltReason(@NonNull ResponseCodeEnum status) implements ExceptionalHaltReason {
    public HandleExceptionHaltReason {
        requireNonNull(status);
    }

    @Override
    public String name() {
        return status.name();
    }

    @Override
    public String getDescription() {
        return "HandleException with status " + status.name() + " thrown during EVM execution";
    }
}
