// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;

public record AssessedFeeWithMultiPayerDeltas(
        @NonNull AssessedCustomFee assessedCustomFee, @Nullable Map<AccountID, Long> multiPayerDeltas) {}
