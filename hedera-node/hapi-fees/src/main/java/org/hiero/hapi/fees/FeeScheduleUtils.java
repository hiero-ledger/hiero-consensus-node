// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeDefinition;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.hiero.hapi.support.fees.ServiceFeeSchedule;

/**
 * Static utility functions to make it easier to create and access
 * the Fee Schedule protobuf objects.
 */
public class FeeScheduleUtils {
    private static final Logger logger = LogManager.getLogger(FeeScheduleUtils.class);
    /** Create an Extra definition. */
    public static ExtraFeeDefinition makeExtraDef(Extra extra, long fee) {
        return ExtraFeeDefinition.newBuilder().name(extra).fee(fee).build();
    }

    /** Create an Extra Included definition for a service */
    public static ExtraFeeReference makeExtraIncluded(Extra extra, int included) {
        return ExtraFeeReference.DEFAULT
                .copyBuilder()
                .name(extra)
                .includedCount(included)
                .build();
    }

    /** Create a service fee for a specific Hedera service */
    public static ServiceFeeDefinition makeServiceFee(
            HederaFunctionality name, long baseFee, ExtraFeeReference... reference) {
        return ServiceFeeDefinition.DEFAULT
                .copyBuilder()
                .name(name)
                .baseFee(baseFee)
                .extras(reference)
                .build();
    }

    /** create a Service definition composed of Service Fees */
    public static ServiceFeeSchedule makeService(String name, ServiceFeeDefinition... services) {
        return ServiceFeeSchedule.DEFAULT
                .copyBuilder()
                .name(name)
                .schedule(services)
                .build();
    }

    // TODO: Probably store all these in a map once when feeSchedule is updated. So, we don't need to iterate everytime
    public static ExtraFeeDefinition lookupExtraFee(FeeSchedule feeSchedule, Extra ref) {
        for (ExtraFeeDefinition def : feeSchedule.extras()) {
            if (def.name().equals(ref)) {
                return def;
            }
        }
        return null;
    }

    /** Lookup a service fee */
    // TODO: Probably store all these in a map once when feeSchedule is updated. So, we don't need to iterate everytime
    public static ServiceFeeDefinition lookupServiceFee(FeeSchedule feeSchedule, HederaFunctionality api) {
        for (ServiceFeeSchedule service : feeSchedule.services()) {
            for (ServiceFeeDefinition def : service.schedule()) {
                if (def.name() == api) {
                    return def;
                }
            }
        }
        return null;
    }

    /**
     * Validate the fee schedule per HIP-1261.
     *
     * @param feeSchedule the fee schedule to validate
     * @return true if valid, false otherwise
     * @throws NullPointerException if feeSchedule is null
     */
    public static boolean isValid(FeeSchedule feeSchedule) {
        requireNonNull(feeSchedule);

        // Node and network are required
        if (feeSchedule.node() == null) {
            logger.error("Node fee is required in simple fee schedule");
            return false;
        }
        if (feeSchedule.network() == null) {
            logger.error("Network fee is required in simple fee schedule");
            return false;
        }

        // Multiplier must be >= 1
        if (feeSchedule.network().multiplier() < 1) {
            logger.error(
                    "Network fee multiplier must be greater than or equal to 1. Given {}",
                    feeSchedule.network().multiplier());
            return false;
        }

        // At least one service required
        if (feeSchedule.services().isEmpty()) {
            logger.error("At least one service is required in simple fee schedule");
            return false;
        }

        // Validate extras: fee > 0, unique names
        Set<Extra> extraNames = new HashSet<>();
        for (ExtraFeeDefinition def : feeSchedule.extras()) {
            if (def.fee() <= 0) {
                logger.error(
                        "Extra fee must be greater than 0 in simple fee schedule for {}, fee {}",
                        def.name(),
                        def.fee());
                return false;
            }
            if (!extraNames.add(def.name())) {
                logger.error("Extra fee names must be unique in simple fee schedule for {}", def.name());
                return false;
            }
        }

        // Validate services
        Set<String> serviceNames = new HashSet<>();
        for (ServiceFeeSchedule service : feeSchedule.services()) {
            // Unique service names
            if (!serviceNames.add(service.name())) {
                logger.error("Service names must be unique in simple fee schedule {}", service.name());
                return false;
            }

            // Non-empty schedule
            if (service.schedule().isEmpty()) {
                logger.error("Service schedule cannot be empty in simple fee schedule {}", service.name());
                return false;
            }

            // Unique transaction names within service
            Set<HederaFunctionality> txNames = new HashSet<>();
            for (ServiceFeeDefinition def : service.schedule()) {
                if (!txNames.add(def.name())) {
                    logger.error(
                            "Transaction names must be unique within service in simple fee schedule {}", def.name());
                    return false;
                }

                // Non-negative baseFee
                if (def.baseFee() < 0) {
                    logger.error("Base fee must be greater than 0 in simple fee schedule {}", def.name());
                    return false;
                }

                // Validate extra references
                Set<Extra> refNames = new HashSet<>();
                for (ExtraFeeReference ref : def.extras()) {
                    if (ref.includedCount() < 0) {
                        logger.error(
                                "Included count must be greater than or equal to 0 in simple fee schedule {}",
                                ref.name());
                        return false;
                    }
                    if (!refNames.add(ref.name())) {
                        logger.error(
                                "Extra fee names must be unique within transaction in simple fee schedule {}",
                                ref.name());
                        return false;
                    }
                    if (lookupExtraFee(feeSchedule, ref.name()) == null) {
                        logger.error("Extra fee {} not found in simple fee schedule", ref.name());
                        return false;
                    }
                }
            }
        }

        // Validate node fee
        if (feeSchedule.node().baseFee() < 0) {
            logger.error(
                    "Node base fee must be greater than or equal to 0 in simple fee schedule. Given {}",
                    feeSchedule.node().baseFee());
            return false;
        }

        Set<Extra> nodeRefNames = new HashSet<>();
        for (ExtraFeeReference ref : feeSchedule.node().extras()) {
            if (ref.includedCount() < 0) {
                logger.error(
                        "Included count must be greater than or equal to 0 in simple fee schedule {}, included count {}",
                        ref.name(),
                        ref.includedCount());
                return false;
            }
            if (!nodeRefNames.add(ref.name())) {
                logger.error("Extra fee names must be unique within node fee in simple fee schedule {}", ref.name());
                return false;
            }
            if (lookupExtraFee(feeSchedule, ref.name()) == null) {
                logger.error("Extra fee {} not found in simple fee schedule", ref.name());
                return false;
            }
        }

        return true;
    }
}
