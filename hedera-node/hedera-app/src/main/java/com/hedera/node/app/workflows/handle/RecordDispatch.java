// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.fees.FeeAccumulator;
import com.hedera.node.app.service.token.records.FinalizeContext;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Set;

public record RecordDispatch(
        @NonNull StreamBuilder streamBuilder,
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
