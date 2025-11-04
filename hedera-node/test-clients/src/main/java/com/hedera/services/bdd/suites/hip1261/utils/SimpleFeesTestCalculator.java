package com.hedera.services.bdd.suites.hip1261.utils;

import com.hedera.hapi.node.base.HederaFunctionality;
import org.hiero.hapi.fees.FeeModel;
import org.hiero.hapi.fees.FeeModelRegistry;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Test calculator for expected fees under Simple Fees.
 * Uses the production fee models to compute rae Node Network and Service fees.
 * Uses the {@link SimpleFeesChargePolicy} to determine the expected charges.
 */

public class SimpleFeesTestCalculator {

    public static final class ExpectedCharges {
        public final long nodeRawFee;
        public final long networkRawFee;
        public final long serviceRawFee;
        public final long payerChargedFee;

        private ExpectedCharges(long nodeRawFee, long networkRawFee, long serviceRawFee, long payerChargedFee) {
            this.nodeRawFee = nodeRawFee;
            this.networkRawFee = networkRawFee;
            this.serviceRawFee = serviceRawFee;
            this.payerChargedFee = payerChargedFee;
        }

        public long rawTotal() {
            return SafeAdd(SafeAdd(nodeRawFee, networkRawFee), serviceRawFee);
        }

        @Override public String toString() {
            return "ExpectedCharges{" +
                    "nodeRawFee=" + nodeRawFee +
                    ", networkRawFee=" + networkRawFee +
                    ", serviceRawFee=" + serviceRawFee +
                    ", payerChargedFee=" + payerChargedFee +
                    '}';
        }
    }

    private SimpleFeeTestCalculator() {}

    /**
     *  Compute the expected charges for a txn given:
     *  the fee schedule
     *  the HederaFunctionality
     *  the extra parameters (SIGNATURES, BYTES, KEYS, etc.)
     *  the charge policy
     *
     * @param schedule - the active simple fee schedule
     * @param api - the HederaFunctionality
     * @param params - the extras parameters
     * @param policy - defines how the payer should be charged for this txn outcome
     * @return the expected charges with raw subtotals and payer-charged total
     */
    public static ExpectedCharges compute(
            final FeeSchedule schedule,
            final HederaFunctionality api,
            final Map<Extra, Long> params,
            final SimpleFeesChargePolicy policy
            ) {
        requireNonNull(schedule, "schedule must not be null");
        requireNonNull(api, "api must not be null");
        requireNonNull(params, "params must not be null");
        requireNonNull(policy, "policy must not be null");

        // Compute the raw fees
        final FeeModel model = FeeModelRegistry.lookupModel(api);
        final FeeResult rawFee = model.computeFee(params, schedule);

        // Determine how much the payer should be charged
        final long payerChargedFee = switch (policy) {
            case UNREADABLE_BYTES_ZERO_PAYER,
                 INVALID_TXN_AT_INGEST_ZERO_PAYER,
                 INVALID_TXN_AT_PRE_HANDLE_ZERO_PAYER -> 0L;
            case UNHANDLED_TXN_FULL_CHARGE,
                 SUCCESS_TXN_FULL_CHARGE -> rawFee.total();
            default -> throw new IllegalArgumentException("Unknown SimpleFeesChargePolicy: " + policy);
        };
        return new ExpectedCharges(
                rawFee.node,
                rawFee.network,
                rawFee.service,
                payerChargedFee
        );
    }

    private static long SafeAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

}
