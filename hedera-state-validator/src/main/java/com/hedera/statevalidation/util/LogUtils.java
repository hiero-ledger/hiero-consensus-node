// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import static com.swirlds.merkledb.files.DataFileCommon.dataLocationToString;

import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.Logger;

// Misc log ops
public final class LogUtils {

    private LogUtils() {}

    public static void printFileDataLocationError(
            @NonNull final Logger logger,
            @NonNull final String message,
            @NonNull final DataFileCollection dfc,
            long dataLocation) {
        final List<DataFileReader> dataFiles = dfc.getAllCompletedFiles();
        logger.error("Error! Details: {}", message);
        logger.error("Data location: {}", dataLocationToString(dataLocation));
        logger.error("Data file collection: ");
        dataFiles.forEach(a -> {
            logger.error("File: {}", a.getPath());
            logger.error("Size: {}", a.getSize());
            logger.error("Metadata: {}", a.getMetadata());
        });
    }
}
