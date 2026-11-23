// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.parseEd25519NodeAdminKeys;
import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.CLASSIC_HAPI_TEST_NETWORK_SIZE;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_LOG;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.NODE_ADMIN_KEYS_JSON;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SWIRLDS_LOG;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.doIfNotInterrupted;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.hapi.node.base.Key;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.support.validators.HgcaaLogValidator;
import com.hedera.services.bdd.junit.support.validators.SwirldsLogValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that initializes validates the streams produced by the
 * target network of the given {@link HapiSpec}. Note it suffices to validate
 * the streams produced by a single node in the network since at minimum
 * a subsequent log validation will fail.
 */
public class LogValidationOp extends UtilOp {
    public enum Scope {
        ANY_NODE,
        ALL_NODES
    }

    private final Scope scope;
    private final Duration delay;

    public LogValidationOp(@NonNull final Scope scope, @NonNull final Duration delay) {
        this.scope = scope;
        this.delay = delay;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        doIfNotInterrupted(() -> MILLISECONDS.sleep(delay.toMillis()));
        // Only nodes that existed at genesis log the admin key overrides
        final int genesisSize = spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork
                ? subProcessNetwork.genesisSize()
                : CLASSIC_HAPI_TEST_NETWORK_SIZE;
        nodesToValidate(spec).forEach(node -> {
            // Validate swirlds.log for all nodes
            final var swirldsLogPath =
                    node.getExternalPath(SWIRLDS_LOG).toAbsolutePath().normalize();
            if (Files.exists(swirldsLogPath)) {
                try {
                    new SwirldsLogValidator(swirldsLogPath.toString()).validate();
                } catch (IOException e) {
                    Assertions.fail("Could not read swirlds.log for node '" + node.getName() + "' " + e);
                }
            }
            try {
                // Only nodes that existed at genesis log the admin key overrides; a node added later is still
                // validated, just without expecting them
                final var overrideNodeAdminKeys = node.getNodeId() < genesisSize
                        ? parseEd25519NodeAdminKeysFrom(node.getExternalPath(NODE_ADMIN_KEYS_JSON)
                                .toAbsolutePath()
                                .normalize()
                                .toString())
                        : Map.<Long, Key>of();
                new HgcaaLogValidator(
                                node.getExternalPath(APPLICATION_LOG)
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString(),
                                overrideNodeAdminKeys)
                        .validate();
            } catch (IOException e) {
                Assertions.fail("Could not read log for node '" + node.getName() + "' " + e);
            }
        });
        return false;
    }

    private List<HederaNode> nodesToValidate(@NonNull final HapiSpec spec) {
        final var network = spec.targetNetworkOrThrow();
        if (scope == Scope.ANY_NODE) {
            return List.of(network.nodes().getFirst());
        }
        // Also validate nodes a test removed from the network; their logs cover the run until they left
        final var nodes = new ArrayList<HederaNode>(network.nodes());
        if (network instanceof SubProcessNetwork subProcessNetwork) {
            nodes.addAll(subProcessNetwork.departedNodes());
        }
        return nodes;
    }

    private static Map<Long, Key> parseEd25519NodeAdminKeysFrom(@NonNull final String loc) {
        try {
            final var json = Files.readString(Paths.get(loc));
            return parseEd25519NodeAdminKeys(json);
        } catch (IOException e) {
            throw new AssertionError("Could not read node admin keys from '" + loc + "' " + e);
        }
    }
}
