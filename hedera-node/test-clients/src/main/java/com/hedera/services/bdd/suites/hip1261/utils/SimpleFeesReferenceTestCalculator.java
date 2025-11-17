// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.hiero.hapi.support.fees.Extra;

/**
 * This is an independent Simple Fees calculator that can be used in tests to verify the correctness of charged fees.
 * The class computes the expected { node, network, service, extras, total } fees for a given transaction
 * from a given JSON schedule parsed into SimpleFeesJsonSchema.
 * This calculator avoids using the production {@code FeeModelRegistry} logic to ensure independence.
 * <h2>Units</h2>
 * <ul>
 *   <li>All values are in <b>tinycents</b>.</li>
 *   <li>Convert to USD for assertions with: {@code tinycents / 100_000_000 / 100}.</li>
 * </ul>
 *
 * <h2>Math (per Simple Fees spec)</h2>
 * <pre>
 * node    = nodeBase + Σextras [ price_per_extra * max(0, count - includedNode(extra)) ]
 *
 * network = node * networkMultiplier
 *
 * service = serviceBase(api) + Σextras [ price_per_extra * max(0, count - includedService(api, extra)) ]
 *
 * total   = node + network + service
 * </pre>
 * <h2>Notes</h2>
 * <ul>
 *   <li>Missing extras, unknown API names, or absent included counts default to 0.</li>
 *   <li>JSON “extra” names are used as plain strings (e.g. "SIGNATURES", "BYTES").</li>
 * </ul>
 */
public class SimpleFeesReferenceTestCalculator {

    /**
     * Convert raw fee components from tinycents to a Result record in USD.
     */
    public record Result(long node, long network, long service, double total) {
        public double totalUsd() {
            return toUsd(total);
        }

        public double nodeUsd() {
            return toUsd(node);
        }

        public double networkUsd() {
            return toUsd(network);
        }

        public double serviceUsd() {
            return toUsd(service);
        }
    }

    /**
     * Defines a Prepared record with the fee schedule data for efficient lookup during computation.
     * @param nodeBase the base node fee
     * @param nodeIncludedByExtra node-level included counts by extra name
     * @param networkMultiplier the network fee multiplier
     * @param priceByExtra price per extra name
     * @param serviceBaseByApi service base fees by HederaFunctionality
     * @param serviceIncludedByApiAndExtra service-level included counts by API and extra name
     */
    public record Prepared(
            long nodeBase,
            Map<Extra, Long> nodeIncludedByExtra,
            int networkMultiplier,
            Map<Extra, Long> priceByExtra,
            Map<HederaFunctionality, Long> serviceBaseByApi,
            Map<HederaFunctionality, Map<Extra, Long>> serviceIncludedByApiAndExtra) {}

    /**
     * Builds a {@link Prepared} schedule from the parsed JSON model.
     * <p>
     * This method:
     * <ol>
     *   <li>Reads node base fee and node-level included extras into a map.</li>
     *   <li>Reads the network multiplier.</li>
     *   <li>Reads global prices for each extra into a map.</li>
     *   <li>For each service operation (API), records its base fee and included counts per-extra.</li>
     * </ol>
     * Any unknown API names in JSON are skipped.
     * Missing values default to zero.
     */
    public static Prepared prepare(SimpleFeesJsonSchema schedule) {
        requireNonNull(schedule, "schedule must not be null");

        // 1. Node base fee, default is 0 if missing
        final long nodeBase = (schedule.node != null) ? schedule.node.baseFee : 0L;

        // 2. Node included counts for extras
        final Map<Extra, Long> nodeIncludedByExtra = new EnumMap<>(Extra.class);
        if (schedule.node != null && schedule.node.extras != null) {
            for (SimpleFeesJsonSchema.Included included : schedule.node.extras) {
                final Extra extra = parseExtra(included.name);
                if (extra != null) {
                    nodeIncludedByExtra.put(extra, included.includedCount);
                }
            }
        }

        // 3. Network multiplier, default is 0 if missing
        final int networkMultiplier = (schedule.network != null) ? schedule.network.multiplier : 0;

        // 4. Global price table for extras (e.g. SIGNATURES -> price)
        final Map<Extra, Long> priceByExtra = new EnumMap<>(Extra.class);
        if (schedule.extras != null) {
            for (SimpleFeesJsonSchema.ExtraPrice extraPrice : schedule.extras) {
                final Extra extra = parseExtra(extraPrice != null ? extraPrice.name : null);
                if (extra != null && extraPrice != null) {
                    priceByExtra.put(extra, extraPrice.fee);
                }
            }
        }

        // 5. Service base fees and included counts by API
        final Map<HederaFunctionality, Long> serviceBaseByApi = new HashMap<>();
        final Map<HederaFunctionality, Map<Extra, Long>> serviceIncludedByApiAndExtra = new HashMap<>();
        if (schedule.services != null) {
            for (SimpleFeesJsonSchema.Service service : schedule.services) {
                if (service == null || service.schedule == null) {
                    continue;
                }

                for (SimpleFeesJsonSchema.ApiFee apiFee : service.schedule) {
                    if (apiFee == null || apiFee.name == null) {
                        continue;
                    }

                    final HederaFunctionality functionality;
                    try {
                        functionality = HederaFunctionality.fromString(apiFee.name);
                    } catch (final IllegalArgumentException e) {
                        continue;
                    }

                    // record the base fee by API
                    serviceBaseByApi.put(functionality, apiFee.baseFee);

                    // record the included counts by API and extra
                    final Map<Extra, Long> includedByExtra = new EnumMap<>(Extra.class);
                    if (apiFee.extras != null) {
                        for (SimpleFeesJsonSchema.Included included : apiFee.extras) {
                            final Extra extra = parseExtra(included != null ? included.name : null);
                            if (extra != null && included != null) {
                                includedByExtra.put(extra, included.includedCount);
                            }
                        }
                    }
                    serviceIncludedByApiAndExtra.put(functionality, unmodifiableMap(includedByExtra));
                }
            }
        }

        return new Prepared(
                nodeBase,
                unmodifiableMap(nodeIncludedByExtra),
                networkMultiplier,
                unmodifiableMap(priceByExtra),
                unmodifiableMap(serviceBaseByApi),
                unmodifiableMap(serviceIncludedByApiAndExtra));
    }

    /**
     * Calculate the expected fees for a given prepared schedule, functionality, and extra counts.
     *
     * @param prepared the prepared fee schedule
     * @param functionality the HederaFunctionality for the transaction
     * @param extrasCounts map of extra name to count (e.g. "SIGNATURES" -> 2L)
     * @return the computed Result with node, network, service, and total fees
     */
    public static Result compute(
            final Prepared prepared, final HederaFunctionality functionality, final Map<Extra, Long> extrasCounts) {
        requireNonNull(prepared, "prepared must not be null");
        requireNonNull(functionality, "functionality must not be null");
        requireNonNull(extrasCounts, "extrasCounts must not be null");

        // Compute node fee
        long nodeFee = safeAdd(
                prepared.nodeBase(),
                computeExtrasFee(prepared.priceByExtra(), prepared.nodeIncludedByExtra(), extrasCounts));

        // Compute network fee
        final long networkFee = safeMultiply(nodeFee, prepared.networkMultiplier());

        // Compute service fee
        long serviceBaseFee = prepared.serviceBaseByApi().getOrDefault(functionality, 0L);
        final Map<Extra, Long> serviceIncluded =
                prepared.serviceIncludedByApiAndExtra().getOrDefault(functionality, Map.of());

        final long serviceFee =
                safeAdd(serviceBaseFee, computeExtrasFee(prepared.priceByExtra(), serviceIncluded, extrasCounts));

        final double totalFee = (1.0 * safeAdd(safeAdd(nodeFee, networkFee), serviceFee));
        return new Result(nodeFee, networkFee, serviceFee, totalFee);
    }

    /**
     * Defines the raw charges components in tinycents.
     */
    public record Charges(long node, long network, long service, double payerCharged) {
        public double payerUsd() {
            return toUsd(payerCharged);
        }
    }

    public static Charges computeWithPolicy(
            final Prepared prepared,
            final HederaFunctionality functionality,
            final Map<Extra, Long> extrasCounts,
            final SimpleFeesChargePolicy policy) {
        requireNonNull(prepared, "prepared must not be null");
        requireNonNull(functionality, "functionality must not be null");
        requireNonNull(extrasCounts, "extrasCounts must not be null");
        requireNonNull(policy, "policy must not be null");

        // Compute raw fees
        final Result rawFees = compute(prepared, functionality, extrasCounts);

        // Determine payer charged fee based on policy
        final double payerChargedFee =
                switch (policy) {
                    case UNREADABLE_BYTES_ZERO_PAYER,
                            INVALID_TXN_AT_INGEST_ZERO_PAYER,
                            INVALID_TXN_AT_PRE_HANDLE_ZERO_PAYER -> 0L;
                    case UNHANDLED_TXN_FULL_CHARGE, SUCCESS_TXN_FULL_CHARGE -> rawFees.total();
                    default -> throw new IllegalArgumentException("Unknown SimpleFeesChargePolicy: " + policy);
                };

        return new Charges(rawFees.node(), rawFees.network(), rawFees.service(), payerChargedFee);
    }

    // ------- Helpers -------

    /**
     * Parse extra name string to Extra enum.
     */
    private static Extra parseExtra(final String extraName) {
        requireNonNull(extraName, "extraName must not be null");
        try {
            return Extra.valueOf(extraName.toUpperCase());
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Computes the extras fee component by applying the per-extra price and included counts.
     * fee = Σ for each extra [ price_per_extra * max(0, count - included(extra)) ]
     */
    private static long computeExtrasFee(
            final Map<Extra, Long> priceByExtra,
            final Map<Extra, Long> includedByExtra,
            final Map<Extra, Long> extrasCounts) {
        long extrasFee = 0L;
        for (Map.Entry<Extra, Long> entry : extrasCounts.entrySet()) {
            final Extra extraName = entry.getKey();
            final long count = nullToZero(entry.getValue());
            final long included = includedByExtra.getOrDefault(extraName, 0L);
            final long pricePerExtra = priceByExtra.getOrDefault(extraName, 0L);
            final long chargeableCount = Math.max(0L, count - included);
            extrasFee = safeAdd(extrasFee, safeMultiply(pricePerExtra, chargeableCount));
        }
        return extrasFee;
    }

    /**
     * Convert tinycents to USD helper.
     */
    public static double toUsd(double tinycents) {
        return tinycents / 100_000_000.0 / 100; // cent to dollar
    }

    /**
     * Safe multiply to Long.MAX_VALUE on overflow.
     */
    private static long safeAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (final ArithmeticException ae) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Safe multiply to Long.MAX_VALUE on overflow.
     */
    private static long safeMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (final ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Convert null Long to zero.
     */
    private static long nullToZero(Long value) {
        return (value != null) ? value : 0L;
    }
}
