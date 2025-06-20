// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls;

import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.Nullable;

public record FailableCallResult(
        ResponseCodeEnum topLevelStatus,
        String topLevelErrorMessage,
        @Nullable ResponseCodeEnum childStatus,
        @Nullable String childErrorMessage) {}
