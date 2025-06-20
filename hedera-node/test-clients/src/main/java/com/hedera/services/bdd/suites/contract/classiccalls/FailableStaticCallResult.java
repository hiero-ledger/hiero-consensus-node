// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls;

import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

public record FailableStaticCallResult(ResponseCodeEnum status, String errorMessage) {}
