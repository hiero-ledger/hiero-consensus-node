// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
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
                .name(name.protoName())
                .baseFee(baseFee)
                .extras(reference)
                .build();
    }
    public static ServiceFeeDefinition makeServiceFee(
            String name, long baseFee, ExtraFeeReference... reference) {
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

    /** Look up the extra fee definition from a fee schedule */
    public static ExtraFeeDefinition lookupExtraFee(FeeSchedule feeSchedule, ExtraFeeReference ref) {
        for (ExtraFeeDefinition def : feeSchedule.extras()) {
            if (def.name().equals(ref.name())) {
                return def;
            }
        }
        return null;
    }

    /** Lookup a service fee */
    public static ServiceFeeDefinition lookupServiceFee(FeeSchedule feeSchedule, String api) {
        for (ServiceFeeSchedule service : feeSchedule.services()) {
            for (ServiceFeeDefinition def : service.schedule()) {
                if (def.name().equals(api)) {
                    return def;
                }
            }
        }
        return null;
    }

    /**
     * Validate the fee schedule. There must be negative fees and no fees
     * bigger than Long.MAX_VALUE. All extras used by a service must
     * be defined. The Node fee must be defined. There must be at least
     * one service defined.
     * @param feeSchedule
     */
    public static boolean validate(FeeSchedule feeSchedule) {
        requireNonNull(feeSchedule);
        for (ExtraFeeDefinition def : feeSchedule.extras()) {
            // no negative values or greater than MAX long
            if (def.fee() < 0) {
                return false;
            }
            if (def.fee() > Long.MAX_VALUE) {
                return false;
            }
        }

        // all referenced extras are defined
        for (ServiceFeeSchedule service : feeSchedule.services()) {
            for (ServiceFeeDefinition def : service.schedule()) {
                for (ExtraFeeReference ref : def.extras()) {
                    lookupExtraFee(feeSchedule, ref);
                }
            }
        }
        // check that the node is valid
        if (feeSchedule.node() == null) {
            return false;
        }
        for (ExtraFeeReference ref : feeSchedule.node().extras()) {
            lookupExtraFee(feeSchedule, ref);
        }

        // check that the services are defined
        if (feeSchedule.services().size() <= 0) {
            return false;
        }
        return true;
    }
}
