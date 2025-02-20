// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.base;

import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;

import com.hedera.hapi.node.base.ResponseType;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Base class for file queries.
 */
public abstract class FileQueryBase extends PaidQueryHandler {

    @Override
    public boolean requiresNodePayment(@NonNull ResponseType responseType) {
        return responseType == ANSWER_ONLY || responseType == ANSWER_STATE_PROOF;
    }

    @Override
    public boolean needsAnswerOnlyCost(@NonNull ResponseType responseType) {
        return COST_ANSWER == responseType;
    }
}
