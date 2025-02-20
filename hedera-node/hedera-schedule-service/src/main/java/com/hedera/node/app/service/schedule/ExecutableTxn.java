// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An executable transaction with the verifier to use for child signature verifications.
 */
public record ExecutableTxn<T extends StreamBuilder>(
        @NonNull TransactionBody body,
        @NonNull AccountID payerId,
        @NonNull Predicate<Key> keyVerifier,
        @NonNull Instant nbf,
        @NonNull Class<T> builderType,
        @NonNull Consumer<T> builderSpec) {}
