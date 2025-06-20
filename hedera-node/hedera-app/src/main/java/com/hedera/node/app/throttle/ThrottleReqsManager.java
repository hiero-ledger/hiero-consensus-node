// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.hedera.node.app.hapi.utils.throttles.BucketThrottle;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;

public class ThrottleReqsManager {
    private final boolean[] passedReq;
    private final List<Pair<DeterministicThrottle, Integer>> allReqs;

    public ThrottleReqsManager(List<Pair<DeterministicThrottle, Integer>> allReqs) {
        this.allReqs = allReqs;
        passedReq = new boolean[allReqs.size()];
    }

    public boolean allReqsMetAt(Instant now, @Nullable final List<ThrottleUsage> throttleUsages) {
        return allVerboseReqsMetAt(now, 0, null, throttleUsages);
    }

    public boolean allReqsMetAt(
            Instant now,
            int nTransactions,
            ScaleFactor scaleFactor,
            @Nullable final List<ThrottleUsage> throttleUsages) {
        return allVerboseReqsMetAt(now, nTransactions, scaleFactor, throttleUsages);
    }

    /**
     * Given a number of logical transactions that had their requirements satisfied by this manager
     * at an earlier time, undoes the claimed capacity for those transactions.
     *
     * <p>We need this to support "reclaiming" capacity in the frontend throttles after an expensive
     * operation (like a {@link com.hedera.hapi.node.base.HederaFunctionality#CRYPTO_TRANSFER} that
     * does an auto-creation):
     * <ol>
     *     <li>Passes the frontend throttle check, using capacity in the buckets there; and,</li>
     *     <li>Fails at consensus, thus <I>not</I> using network capacity for the expensive operation in the end.</li>
     * </ol>
     *
     * @param nTransactions the number of transactions to undo
     */
    public void undoClaimedReqsFor(int nTransactions) {
        for (int i = 0; i < passedReq.length; i++) {
            final var req = allReqs.get(i);
            final var opsRequired = req.getRight();
            final var bucket = req.getLeft();
            bucket.leakCapacity(nTransactions * opsRequired * BucketThrottle.capacityUnitsPerTxn());
        }
    }

    private boolean allVerboseReqsMetAt(
            Instant now,
            int nTransactions,
            ScaleFactor scaleFactor,
            @Nullable final List<ThrottleUsage> throttleUsages) {
        var allPassed = true;
        for (int i = 0; i < passedReq.length; i++) {
            var req = allReqs.get(i);
            var opsRequired = req.getRight();
            if (scaleFactor != null) {
                opsRequired = scaleFactor.scaling(nTransactions * opsRequired);
            }
            passedReq[i] = req.getLeft().allow(opsRequired, now);
            if (throttleUsages != null && passedReq[i]) {
                throttleUsages.add(new DeterministicThrottleUsage(req.getLeft(), opsRequired));
            }
            allPassed &= passedReq[i];
        }

        return allPassed;
    }

    public List<DeterministicThrottle> managedThrottles() {
        return allReqs.stream().map(Pair::getLeft).toList();
    }

    public String asReadableRequirements() {
        return "min{" + allReqs.stream().map(this::readable).collect(Collectors.joining(", ")) + "}";
    }

    private String readable(Pair<DeterministicThrottle, Integer> req) {
        var throttle = req.getLeft();
        return approximateTps(req.getRight(), throttle.mtps()) + " tps (" + throttle.name() + ")";
    }

    private String approximateTps(int logicalTpsReq, long bucketMtps) {
        return String.format("%.2f", (1.0 * bucketMtps) / 1000.0 / logicalTpsReq);
    }
}
