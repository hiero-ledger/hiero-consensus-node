// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.state;

import static com.hedera.statevalidation.parameterresolver.InitUtils.CONFIGURATION;
import static com.swirlds.common.merkle.utility.MerkleUtils.rehashTree;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.statevalidation.parameterresolver.HashInfo;
import com.hedera.statevalidation.parameterresolver.HashInfoResolver;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import java.util.Arrays;
import java.util.List;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StateResolver.class, ReportResolver.class, SlackReportGenerator.class, HashInfoResolver.class})
@Tag("rehash")
public class Rehash {

    /**
     * This parameter defines how deep the hash tree should be traversed.
     * Note that it doesn't go below the top level of VirtualMap even if the depth is set to a higher value.
     */
    public static final int HASH_DEPTH = 5;

    @Test
    void reHash(DeserializedSignedState deserializedSignedState, Report report) {

        MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(CONFIGURATION);
        final Hash originalHash = deserializedSignedState.originalHash();
        final Hash calculatedHash = rehashTree(
                merkleCryptography,
                deserializedSignedState.reservedSignedState().get().getState().getRoot());

        // Add data to the report, adding it before the assertion so that the report is written even if the test fails
        var stateReport = report.getStateReport();
        stateReport.setRootHash(originalHash.toString());
        stateReport.setCalculatedHash(originalHash.toString());
        report.setRoundNumber(
                deserializedSignedState.reservedSignedState().get().getRound());

        assertEquals(originalHash, calculatedHash);
    }

    /**
     * This test validates the Merkle tree structure of the state.
     *
     * @param deserializedSignedState The deserialized signed state, propagated by the StateResolver.
     * @param report                  The report object, propagated by the ReportResolver.
     * @param hashInfo                The hash info object, propagated by the HashInfoResolver.
     */
    @Test
    void validateMerkleTree(DeserializedSignedState deserializedSignedState, Report report, HashInfo hashInfo) {

        var platformStateFacade = PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
        var infoStringFromState = platformStateFacade.getInfoString(
                deserializedSignedState.reservedSignedState().get().getState(), HASH_DEPTH);

        final var originalLines = Arrays.asList(hashInfo.content().split("\n"));
        final var fullList = Arrays.asList(infoStringFromState.split("\n"));
        // skipping irrelevant lines
        final var revisedLines = filterLines(fullList);

        assertEquals(originalLines, revisedLines, "The Merkle tree structure does not match the expected state.");
    }

    private List<String> filterLines(List<String> lines) {
        int i = 0;
        for (; i < lines.size(); i++) {
            if (lines.get(i).contains("(root)")) {
                break;
            }
        }
        return lines.subList(i, lines.size());
    }
}
