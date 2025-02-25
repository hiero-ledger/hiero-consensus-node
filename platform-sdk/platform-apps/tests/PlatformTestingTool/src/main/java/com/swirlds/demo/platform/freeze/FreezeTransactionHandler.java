// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.freeze;

import com.swirlds.demo.platform.fs.stresstest.proto.FreezeTransaction;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.State;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class FreezeTransactionHandler {
    private static final Logger logger = LogManager.getLogger(FreezeTransactionHandler.class);
    private static final Marker LOGM_FREEZE = MarkerManager.getMarker("FREEZE");

    public static boolean freeze(
            final FreezeTransaction transaction, final PlatformStateFacade platformStateFacade, final State state) {
        logger.debug(LOGM_FREEZE, "Handling FreezeTransaction: " + transaction);
        try {

            platformStateFacade.bulkUpdateOf(
                    state, v -> v.setFreezeTime(Instant.ofEpochSecond(transaction.getStartTimeEpochSecond())));
            return true;
        } catch (IllegalArgumentException ex) {
            logger.warn(LOGM_FREEZE, "FreezeTransactionHandler::freeze fails. {}", ex.getMessage());
            return false;
        }
    }
}
