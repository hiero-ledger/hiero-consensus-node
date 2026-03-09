// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.synchronous;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Workflow that submits a transaction and blocks until the {@code TransactionRecord} is available.
 */
public interface SynchronousWorkflow {

    /**
     * Submit the transaction contained in {@code requestBuffer} and block until the corresponding
     * {@code TransactionRecord} has been written to {@code responseBuffer}.
     *
     * @param requestBuffer  the serialised {@code Transaction} proto bytes
     * @param responseBuffer the buffer into which the serialised {@code TransactionRecord} is written
     */
    void submitAndWait(@NonNull Bytes requestBuffer, @NonNull BufferedData responseBuffer);
}
