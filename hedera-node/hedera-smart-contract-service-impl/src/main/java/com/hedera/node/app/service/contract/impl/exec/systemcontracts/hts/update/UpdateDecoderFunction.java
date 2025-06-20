// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;

@FunctionalInterface
public interface UpdateDecoderFunction {

    TransactionBody decode(@NonNull final HtsCallAttempt input);
}
