// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.hedera.node.config.types.Profile;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * @param throttleTransactionQueueSize Stop accepting new non-system transactions into the transaction queue if it
 *                                     exceeds this limit
 * @param maxTransactionBytesPerEvent  the maximum number of bytes that a single event may contain, not including the
 *                                     event headers. if a single transaction exceeds this limit, then the event will
 *                                     contain the single transaction only
 */
@ConfigData("hedera")
public record HederaConfig(
        @ConfigProperty(defaultValue = "1001") @NetworkProperty long firstUserEntity,
        @ConfigProperty(defaultValue = "0") @NodeProperty long realm,
        @ConfigProperty(defaultValue = "0") @NodeProperty long shard,
        @ConfigProperty(value = "config.version", defaultValue = "0") @NetworkProperty int configVersion,
        @ConfigProperty(value = "nodeTransaction.maxBytes", defaultValue = "2621440") @NetworkProperty
                int nodeTransactionMaxBytes,
        @ConfigProperty(value = "transaction.maxBytes", defaultValue = "6144") @NetworkProperty int transactionMaxBytes,
        @ConfigProperty(value = "transaction.maxMemoUtf8Bytes", defaultValue = "100") @NetworkProperty
                int transactionMaxMemoUtf8Bytes,
        @ConfigProperty(value = "transaction.maxValidDuration", defaultValue = "180") @NetworkProperty
                long transactionMaxValidDuration,
        @ConfigProperty(value = "transaction.minValidDuration", defaultValue = "15") @NetworkProperty
                long transactionMinValidDuration,
        @ConfigProperty(value = "transaction.minValidityBufferSecs", defaultValue = "10") @NetworkProperty
                int transactionMinValidityBufferSecs,
        @ConfigProperty(value = "allowances.maxTransactionLimit", defaultValue = "20") @NetworkProperty
                int allowancesMaxTransactionLimit,
        @ConfigProperty(value = "allowances.maxAccountLimit", defaultValue = "100") @NetworkProperty
                int allowancesMaxAccountLimit,
        @ConfigProperty(defaultValue = "data/onboard/exportedAccount.txt") @NodeProperty String accountsExportPath,
        @ConfigProperty(value = "prefetch.queueCapacity", defaultValue = "70000") @NodeProperty
                int prefetchQueueCapacity,
        @ConfigProperty(value = "prefetch.threadPoolSize", defaultValue = "4") @NodeProperty int prefetchThreadPoolSize,
        @ConfigProperty(value = "prefetch.codeCacheTtlSecs", defaultValue = "600") @NodeProperty
                int prefetchCodeCacheTtlSecs,
        @ConfigProperty(value = "profiles.active", defaultValue = "PROD") @NodeProperty Profile activeProfile,
        @ConfigProperty(value = "workflow.verificationTimeoutMS", defaultValue = "20000") @NetworkProperty
                long workflowVerificationTimeoutMS,
        // FUTURE: Set<HederaFunctionality>.
        @ConfigProperty(value = "ingestThrottle.enabled", defaultValue = "true") @NetworkProperty
                boolean ingestThrottleEnabled,
        @ConfigProperty(value = "transaction.throttleTransactionQueueSize", defaultValue = "100000") @NodeProperty
                int throttleTransactionQueueSize,
        @ConfigProperty(value = "transaction.maxTransactionBytesPerEvent", defaultValue = "2621440") @NetworkProperty
                int maxTransactionBytesPerEvent) {}
