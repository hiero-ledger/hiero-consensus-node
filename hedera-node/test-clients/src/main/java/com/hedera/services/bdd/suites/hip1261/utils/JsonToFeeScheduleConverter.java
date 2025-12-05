// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import static java.lang.Math.toIntExact;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.ArrayList;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeDefinition;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.hiero.hapi.support.fees.ServiceFeeSchedule;

public class JsonToFeeScheduleConverter {

    /**
     * Mapping from JSON API names to HederaFunctionality.
     */
    private static HederaFunctionality parseApiName(String name) {
        try {
            return HederaFunctionality.fromString(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Mapping from JSON extra names to Extra enums.
     */
    private static Extra parseExtraName(String name) {
        try {
            return Extra.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Convert a SimpleFeesJsonSchema to a FeeSchedule.
     * @param schedule the JSON fee schedule
     * @return the corresponding FeeSchedule
     */
    public static FeeSchedule toFeeSchedule(final SimpleFeesJsonSchema schedule) {
        // ------- Global extras price table -------
        final var extraDefinitions = new ArrayList<ExtraFeeDefinition>();
        if (schedule.extras != null) {
            for (final var extraEntry : schedule.extras) {
                if (extraEntry == null || extraEntry.name == null) {
                    continue;
                }
                final Extra extra = parseExtraName(extraEntry.name);
                if (extra != null) {
                    extraDefinitions.add(makeExtraDef(extra, extraEntry.fee));
                }
            }
        }

        // ------- Node (base + included extras) -------
        NodeFee nodeFee = NodeFee.DEFAULT;
        if (schedule.node != null) {
            final var nodeBuilder = NodeFee.DEFAULT.copyBuilder().baseFee(schedule.node.baseFee);
            if (schedule.node.extras != null) {
                for (final var includedExtra : schedule.node.extras) {
                    if (includedExtra == null || includedExtra.name == null) {
                        continue;
                    }
                    final Extra extra = parseExtraName(includedExtra.name);
                    if (extra != null && includedExtra.includedCount > 0) {
                        nodeBuilder.extras(makeExtraIncluded(extra, toIntExact(includedExtra.includedCount)));
                    }
                }
            }
            nodeFee = nodeBuilder.build();
        }

        // ------- Network multiplier -------
        final var networkFee = (schedule.network != null)
                ? NetworkFee.DEFAULT
                        .copyBuilder()
                        .multiplier(schedule.network.multiplier)
                        .build()
                : NetworkFee.DEFAULT;

        // ------- Services and their operations -------

        final var services = new ArrayList<ServiceFeeSchedule>();

        if (schedule.services != null) {
            for (final var serviceEntry : schedule.services) {
                if (serviceEntry == null || serviceEntry.name == null || serviceEntry.schedule == null) {
                    continue;
                }

                // List of ServiceFeeDefinition entries for this service
                final var serviceFees = new ArrayList<ServiceFeeDefinition>();

                for (final var apiFeeEntry : serviceEntry.schedule) {
                    if (apiFeeEntry == null || apiFeeEntry.name == null) {
                        continue;
                    }

                    // Map API name to HederaFunctionality
                    final HederaFunctionality functionality = parseApiName(apiFeeEntry.name);
                    if (functionality == null) {
                        continue;
                    }

                    // Build list of included extras for this API
                    final var included = new ArrayList<ExtraFeeReference>();
                    if (apiFeeEntry.extras != null) {
                        for (final var includedExtra : apiFeeEntry.extras) {
                            if (includedExtra == null || includedExtra.name == null) {
                                continue;
                            }
                            final Extra extra = parseExtraName(includedExtra.name);
                            if (extra != null && includedExtra.includedCount > 0) {
                                included.add(makeExtraIncluded(extra, toIntExact(includedExtra.includedCount)));
                            }
                        }
                    }

                    // Create ServiceFeeDefinition for this API
                    final var serviceFee = makeServiceFee(
                            functionality, apiFeeEntry.baseFee, included.toArray(new ExtraFeeReference[0]));
                    serviceFees.add(serviceFee);
                }

                // Create ServiceFeeSchedule for this service
                if (!serviceFees.isEmpty()) {
                    final var serviceFeeSchedule =
                            makeService(serviceEntry.name, serviceFees.toArray(new ServiceFeeDefinition[0]));
                    services.add(serviceFeeSchedule);
                }
            }
        }

        // Build and return the final FeeSchedule
        final var builder = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(extraDefinitions)
                .node(nodeFee)
                .network(networkFee)
                .services(services);

        return builder.build();
    }
}
