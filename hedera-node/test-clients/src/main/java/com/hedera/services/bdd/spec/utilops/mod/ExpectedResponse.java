// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * Encapsulates the expected response to a transaction; if the set of permissible
 * prechecks is left null, it is assumed to contain only {@link ResponseCodeEnum#OK}.
 *
 * @param permissiblePrechecks a set of failure statuses if the transaction should not make it past ingest
 * @param permissibleOutcomes a set of permissible status responses at consensus
 */
public record ExpectedResponse(
        @Nullable Set<ResponseCodeEnum> permissiblePrechecks, @Nullable Set<ResponseCodeEnum> permissibleOutcomes) {
    private static final Set<ResponseCodeEnum> SUCCESS = EnumSet.of(ResponseCodeEnum.SUCCESS);

    public static ExpectedResponse atIngest(@NonNull final ResponseCodeEnum status) {
        return new ExpectedResponse(EnumSet.of(status), null);
    }

    public static ExpectedResponse atIngestOneOf(@NonNull final ResponseCodeEnum... statuses) {
        return new ExpectedResponse(EnumSet.copyOf(asList(statuses)), null);
    }

    public static ExpectedResponse atConsensus(@NonNull final ResponseCodeEnum status) {
        return new ExpectedResponse(null, EnumSet.of(status));
    }

    public static ExpectedResponse atConsensusOneOf(@NonNull final ResponseCodeEnum... statuses) {
        return new ExpectedResponse(null, EnumSet.copyOf(asList(statuses)));
    }

    public void customize(@NonNull final HapiTxnOp<?> op) {
        if (permissiblePrechecks != null) {
            op.hasPrecheckFrom(permissiblePrechecks.toArray(ResponseCodeEnum[]::new));
        } else {
            requireNonNull(permissibleOutcomes);
            op.hasKnownStatusFrom(permissibleOutcomes.toArray(ResponseCodeEnum[]::new));
        }
    }

    public boolean isSuccess() {
        return SUCCESS.equals(permissibleOutcomes);
    }
}
