// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle.ops;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode.ReassignPorts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.lifecycle.AbstractLifecycleOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Shuts down the selected node or nodes specified by the {@link NodeSelector}.
 */
public class TryToStartNodesOp extends AbstractLifecycleOp {
    private static final Logger log = LogManager.getLogger(TryToStartNodesOp.class);

    private final int configVersion;
    private final ReassignPorts reassignPorts;
    private final Map<String, String> envOverrides;

    public TryToStartNodesOp(@NonNull final NodeSelector selector) {
        this(selector, 0, ReassignPorts.NO, Map.of());
    }

    public TryToStartNodesOp(@NonNull final NodeSelector selector, final int configVersion) {
        this(selector, configVersion, ReassignPorts.NO, Map.of());
    }

    public TryToStartNodesOp(
            @NonNull final NodeSelector selector,
            final int configVersion,
            @NonNull final ReassignPorts reassignPorts,
            @NonNull final Map<String, String> envOverrides) {
        super(selector);
        this.configVersion = configVersion;
        this.reassignPorts = requireNonNull(reassignPorts);
        this.envOverrides = requireNonNull(envOverrides);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        if (reassignPorts == ReassignPorts.YES) {
            if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork)) {
                throw new IllegalStateException("Can only reassign ports for a SubProcessNetwork");
            }
            subProcessNetwork.refreshOverrideWithNewPorts();
        }
        return super.submitOp(spec);
    }

    @Override
    protected void run(@NonNull final HederaNode node, @NonNull HapiSpec spec) {
        log.info("Starting node '{}' - {} using overrides {}", node.getName(), node.metadata(), envOverrides);
        try {
            if (!(node instanceof SubProcessNode subProcessNode)) {
                throw new IllegalStateException("Node is not a SubProcessNode");
            }
            subProcessNode.startWithConfigVersion(configVersion, envOverrides);
        } catch (Exception e) {
            log.error("Node '{}' failed to start", node, e);
            Assertions.fail("Node " + node + " failed to start (" + e.getMessage() + ")");
        }
        log.info("Node '{}' has started", node.getName());
    }
}
