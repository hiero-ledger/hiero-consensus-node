// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.verification.traceability;

import com.hedera.services.bdd.spec.assertions.matchers.TransactionSidecarRecordMatcher;

public record ExpectedSidecar(String spec, TransactionSidecarRecordMatcher expectedSidecarRecord) {}
