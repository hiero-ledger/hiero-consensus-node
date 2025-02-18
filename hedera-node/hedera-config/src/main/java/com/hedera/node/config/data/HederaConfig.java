/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.hedera.node.config.types.Profile;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("hedera")
public record HederaConfig(
        @ConfigProperty(defaultValue = "1001") @NetworkProperty long firstUserEntity,
        @ConfigProperty(defaultValue = "0") @NodeProperty long realm,
        @ConfigProperty(defaultValue = "0") @NodeProperty long shard,
        @ConfigProperty(value = "config.version", defaultValue = "0") @NetworkProperty int configVersion,
        @ConfigProperty(value = "transaction.eip2930.enabled", defaultValue = "true") @NetworkProperty
                boolean eip2930Enabled,
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
        @ConfigProperty(value = "allowances.isEnabled", defaultValue = "true") @NetworkProperty
                boolean allowancesIsEnabled,
        @ConfigProperty(defaultValue = "data/onboard/exportedAccount.txt") @NodeProperty String accountsExportPath,
        @ConfigProperty(defaultValue = "false") @NodeProperty boolean exportAccountsOnStartup,
        @ConfigProperty(value = "prefetch.queueCapacity", defaultValue = "70000") @NodeProperty
                int prefetchQueueCapacity,
        @ConfigProperty(value = "prefetch.threadPoolSize", defaultValue = "4") @NodeProperty int prefetchThreadPoolSize,
        @ConfigProperty(value = "prefetch.codeCacheTtlSecs", defaultValue = "600") @NodeProperty
                int prefetchCodeCacheTtlSecs,
        @ConfigProperty(value = "profiles.active", defaultValue = "PROD") @NodeProperty Profile activeProfile,
        @ConfigProperty(value = "workflow.verificationTimeoutMS", defaultValue = "20000") @NetworkProperty
                long workflowVerificationTimeoutMS,
        // FUTURE: Set<HederaFunctionality>.
        @ConfigProperty(value = "workflows.enabled", defaultValue = "true") @NetworkProperty String workflowsEnabled,
        @ConfigProperty(value = "ingestThrottle.enabled", defaultValue = "true") @NetworkProperty
                boolean ingestThrottleEnabled) {}
