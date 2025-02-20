// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.verification.traceability;

import com.hedera.services.bdd.spec.assertions.matchers.TransactionSidecarRecordMatcher;
import com.hedera.services.stream.proto.TransactionSidecarRecord;

public record MismatchedSidecar(
        TransactionSidecarRecordMatcher expectedSidecarRecordMatcher, TransactionSidecarRecord actualSidecarRecord) {}
