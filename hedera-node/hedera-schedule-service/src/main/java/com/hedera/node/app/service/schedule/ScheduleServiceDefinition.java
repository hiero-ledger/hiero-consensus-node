// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Transactions and queries for the Schedule Service.
 */
@SuppressWarnings("java:S6548")
public final class ScheduleServiceDefinition implements RpcServiceDefinition {
    /**
     * The global singleton ScheduleServiceDefinition INSTANCE.
     */
    public static final ScheduleServiceDefinition INSTANCE = new ScheduleServiceDefinition();

    /**
     * The set of methods supported by the Schedule Service.
     */
    private static final Set<RpcMethodDefinition<?, ?>> methods = Set.of(
            new RpcMethodDefinition<>("createSchedule", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("signSchedule", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("deleteSchedule", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("getScheduleInfo", Query.class, Response.class));

    private ScheduleServiceDefinition() {
        // Just something to keep the Gradle build believing we have a non-transitive
        // "requires" and hence preserving our module-info.class in the compiled JAR
        requireNonNull(CommonUtils.class);
    }

    /**
     * Returns the base path for the Schedule Service.
     *
     * @return the base path
     */
    @Override
    @NonNull
    public String basePath() {
        return "proto.ScheduleService";
    }

    /**
     * Returns the set of methods supported by the Schedule Service.
     *
     * @return the methods
     */
    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
