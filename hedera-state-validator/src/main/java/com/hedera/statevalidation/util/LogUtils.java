// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import static com.swirlds.merkledb.files.DataFileCommon.dataLocationToString;

import com.hedera.statevalidation.validator.model.DiskDataItem;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.Logger;

// Misc log ops
public final class LogUtils {

    private LogUtils() {}

    public static void printFileDataLocationError(
            @NonNull final Logger logger, @NonNull final String message, long dataLocation) {
        logger.error("Error! Details: {}", message);
        logger.error("Data location: {}", dataLocationToString(dataLocation));
    }

    public static void printFileDataLocationError(
            @NonNull final Logger logger, @NonNull final String message, @NonNull final DiskDataItem diskDataItem) {
        logger.error("Error! Details: {}", message);
        logger.error("Item Data: {}", diskDataItem.type());
        logger.error("Item Data Location: {}", diskDataItem.location());
    }
}
