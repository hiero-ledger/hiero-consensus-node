// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import com.hedera.hapi.node.transaction.Query;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/** A workflow for processing queries. */
public interface QueryWorkflow {

    /**
     * Called to handle a single query.
     *
     * @param requestBuffer The raw protobuf query bytes. Must be a {@link Query} object.
     * @param responseBuffer The raw protobuf response bytes.
     */
    void handleQuery(@NonNull Bytes requestBuffer, @NonNull BufferedData responseBuffer);
}
