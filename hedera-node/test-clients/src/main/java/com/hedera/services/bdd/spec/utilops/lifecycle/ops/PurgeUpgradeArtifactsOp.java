// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle.ops;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.UPGRADE_ARTIFACTS_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CANDIDATE_ROSTER_JSON;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.rm;

import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.lifecycle.AbstractLifecycleOp;
import edu.umd.cs.findbugs.annotations.NonNull;

public class PurgeUpgradeArtifactsOp extends AbstractLifecycleOp {
    public PurgeUpgradeArtifactsOp(@NonNull NodeSelector selector) {
        super(selector);
    }

    @Override
    protected void run(@NonNull final HederaNode node, @NonNull final HapiSpec spec) {
        final var upgradeArtifactsLoc = node.getExternalPath(UPGRADE_ARTIFACTS_DIR);
        rm(upgradeArtifactsLoc);
        guaranteedExtantDir(upgradeArtifactsLoc);
        rm(node.metadata().workingDirOrThrow().resolve(CANDIDATE_ROSTER_JSON));
    }
}
