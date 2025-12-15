// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.statevalidation.report.SlackReportGenerator;
import com.hedera.statevalidation.util.junit.HashInfo;
import com.hedera.statevalidation.util.junit.HashInfoResolver;
import com.hedera.statevalidation.util.junit.StateResolver;
import com.hedera.statevalidation.validator.v2.pipeline.RehashTaskExecutor;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StateResolver.class, SlackReportGenerator.class, HashInfoResolver.class})
@Tag("rehash")
public class Rehash {

    private static final Logger logger = LogManager.getLogger(Rehash.class);

    /**
     * This parameter defines how deep the hash tree should be traversed.
     * Note that it doesn't go below the top level of VirtualMap even if the depth is set to a higher value.
     */
    public static final int HASH_DEPTH = 5;

    @Test
    void reHash(DeserializedSignedState deserializedSignedState) throws Exception {
        final VirtualMap vm = (VirtualMap)
                deserializedSignedState.reservedSignedState().get().getState().getRoot();
        final RecordAccessor records = vm.getRecords();

        final VirtualMapMetadata metadata = vm.getMetadata();
        final long firstLeafPath = metadata.getFirstLeafPath();
        final long lastLeafPath = metadata.getLastLeafPath();
        logger.info("Doing full rehash for the path range: {} - {} in the VirtualMap", firstLeafPath, lastLeafPath);

        final long start = System.currentTimeMillis();
        final RehashTaskExecutor executor = new RehashTaskExecutor(records, firstLeafPath, lastLeafPath);
        final Hash computedHash = executor.execute();
        assertEquals(deserializedSignedState.originalHash(), computedHash);
        logger.info("It took {} seconds to rehash the VirtualMap", (System.currentTimeMillis() - start) / 1000);
    }

    /**
     * This test validates the Merkle tree structure of the state.
     *
     * @param deserializedSignedState The deserialized signed state, propagated by the StateResolver.
     * @param hashInfo                The hash info object, propagated by the HashInfoResolver.
     */
    @Test
    void validateMerkleTree(DeserializedSignedState deserializedSignedState, HashInfo hashInfo) {

        var platformStateFacade = PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
        var infoStringFromState = platformStateFacade.getInfoString(
                deserializedSignedState.reservedSignedState().get().getState(), HASH_DEPTH);

        final var originalLines = Arrays.asList(hashInfo.content().split("\n")).getFirst();
        final var fullList = Arrays.asList(infoStringFromState.split("\n"));
        // skipping irrelevant lines, capturing only the one with the root hash
        final var revisedLines = filterLines(fullList);

        assertEquals(originalLines, revisedLines, "The Merkle tree structure does not match the expected state.");
    }

    private String filterLines(List<String> lines) {
        for (String line : lines) {
            if (line.contains("(root)")) {
                return line;
            }
        }
        return "root hash not found";
    }
}
