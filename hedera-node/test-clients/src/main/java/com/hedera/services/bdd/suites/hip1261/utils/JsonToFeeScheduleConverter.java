// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import com.hedera.hapi.node.base.HederaFunctionality;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeDefinition;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.hiero.hapi.support.fees.ServiceFeeSchedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_DELETE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_GET_TOPIC_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
import static java.lang.Math.toIntExact;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;

public class JsonToFeeScheduleConverter {

    /**
     * Mapping from JSON API names to HederaFunctionality enums.
     */
    private static final Map<String, HederaFunctionality> API_NAME_MAP = Map.ofEntries(
            Map.entry("ConsensusCreateTopic", CONSENSUS_CREATE_TOPIC),
            Map.entry("ConsensusUpdateTopic", CONSENSUS_UPDATE_TOPIC),
            Map.entry("ConsensusDeleteTopic", CONSENSUS_DELETE_TOPIC),
            Map.entry("ConsensusSubmitMessage", CONSENSUS_SUBMIT_MESSAGE),
            Map.entry("ConsensusGetTopicInfo", CONSENSUS_GET_TOPIC_INFO)
    );

    /**
     * Extra name mapping (string -> enum).
     */
    private static final Map<String, Extra> EXTRA_NAME_MAP = new HashMap<>();
    static {
        EXTRA_NAME_MAP.put("SIGNATURES", Extra.SIGNATURES);
        EXTRA_NAME_MAP.put("BYTES", Extra.BYTES);
        EXTRA_NAME_MAP.put("KEYS", Extra.KEYS);
        EXTRA_NAME_MAP.put("ACCOUNTS", Extra.ACCOUNTS);
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
                final var extra = EXTRA_NAME_MAP.get(extraEntry.name);
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
                    final var extra = EXTRA_NAME_MAP.get(includedExtra.name);
                    if (extra != null && includedExtra.includedCount > 0) {
                        nodeBuilder.extras(makeExtraIncluded(extra, toIntExact(includedExtra.includedCount)));
                    }
                }
            }
            nodeFee = nodeBuilder.build();
        }

        // ------- Network multiplier -------
        final var networkFee = (schedule.network != null)
                ? NetworkFee.DEFAULT.copyBuilder().multiplier(schedule.network.multiplier).build()
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
                    final var functionality = API_NAME_MAP.get(apiFeeEntry.name);
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
                            final var extra = EXTRA_NAME_MAP.get(includedExtra.name);
                            if (extra != null && includedExtra.includedCount > 0) {
                                included.add(makeExtraIncluded(extra, toIntExact(includedExtra.includedCount)));
                            }
                        }
                    }

                    // Create ServiceFeeDefinition for this API
                    final var serviceFee = makeServiceFee(
                            functionality,
                            apiFeeEntry.baseFee,
                            included.toArray(new ExtraFeeReference[0])
                    );
                    serviceFees.add(serviceFee);
                }

                // Create ServiceFeeSchedule for this service
                if (!serviceFees.isEmpty()) {
                    final var serviceFeeSchedule = makeService(
                            serviceEntry.name,
                            serviceFees.toArray(new ServiceFeeDefinition[0])
                    );
                    services.add(serviceFeeSchedule);
                }
            }
        }

        // Build and return the final FeeSchedule
        final var builder = FeeSchedule.DEFAULT.copyBuilder()
                .extras(extraDefinitions)
                .node(nodeFee)
                .network(networkFee)
                .services(services);

        return builder.build();
    }
}
