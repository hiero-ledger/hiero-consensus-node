// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenTransactionBodyWrapper;
import edu.umd.cs.findbugs.annotations.NonNull;

@FunctionalInterface
public interface UpdateDecoderFunction {

    TokenTransactionBodyWrapper decode(@NonNull final HtsCallAttempt input);
}
