/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.workflows.handle;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.fees.FeeAccumulator;
import com.hedera.node.app.service.token.records.FinalizeContext;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Set;

public record RecordDispatch(
        @NonNull StreamBuilder recordBuilder,
        @NonNull Configuration config,
        @NonNull Fees fees,
        @NonNull TransactionInfo txnInfo,
        @NonNull AccountID payerId,
        @NonNull ReadableStoreFactory readableStoreFactory,
        @NonNull FeeAccumulator feeAccumulator,
        @NonNull AppKeyVerifier keyVerifier,
        @NonNull NodeInfo creatorInfo,
        @NonNull Instant consensusNow,
        @NonNull Set<Key> requiredKeys,
        @NonNull Set<Account> hollowAccounts,
        @NonNull HandleContext handleContext,
        @NonNull SavepointStackImpl stack,
        @NonNull HandleContext.TransactionCategory txnCategory,
        @NonNull FinalizeContext finalizeContext,
        @NonNull PreHandleResult preHandleResult,
        @NonNull HandleContext.ConsensusThrottling throttleStrategy,
        @Nullable FeeCharging customFeeCharging)
        implements Dispatch {}
