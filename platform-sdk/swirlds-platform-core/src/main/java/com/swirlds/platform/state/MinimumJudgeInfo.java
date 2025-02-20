// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the minimum ancient indicator for all judges in a particular round.
 *
 * @param round                        the round number
 * @param minimumJudgeAncientThreshold the minimum ancient threshold for all judges for a given round. Will be a
 *                                     generation if the birth round migration has not yet happened, will be a birth
 *                                     round otherwise.
 */
public record MinimumJudgeInfo(long round, long minimumJudgeAncientThreshold) {

}
