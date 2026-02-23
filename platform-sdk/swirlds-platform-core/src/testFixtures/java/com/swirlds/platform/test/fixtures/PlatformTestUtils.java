// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.config.PathsConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.StandardGraphGenerator;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.EventSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.StandardEventSource;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

/**
 * Platform level unit test base class for common setup and teardown.
 */
public class PlatformTestUtils {

    public static final String TEST_MARKER_FILE_DIRECTORY = "marker_files";

    /**
     * Creates a default platform context for the tests
     */
    @NonNull
    public static PlatformContext createDefaultPlatformContext() {
        return createPlatformContext(null, null);
    }

    /**
     * Creates a platform context for the tests with the given builder modifications. Modifications are applied in the
     * following order:
     * <ol>
     *     <li>The config modifications are applied</li>
     *     <li>The temp directory is added to the config for marker files</li>
     *     <li>The platform context builder modifications are applied</li>
     * </ol>
     * <p>
     * Any configuration set by the platform context builder modifying method overrides the configuration created by
     * the config modifier. Best practice is to set configuration through the config modifier and all other platform
     * context variables through the platform context modifier.
     *
     * @param platformContextModifier the function to modify the platform context builder
     * @param configModifier          the function to modify the test config builder
     * @return the platform context
     */
    @NonNull
    public static PlatformContext createPlatformContext(
            @Nullable final Function<TestPlatformContextBuilder, TestPlatformContextBuilder> platformContextModifier,
            @Nullable final Function<TestConfigBuilder, TestConfigBuilder> configModifier) {
        final TestPlatformContextBuilder platformContextBuilder = TestPlatformContextBuilder.create();
        final TestConfigBuilder configBuilder = new TestConfigBuilder();
        if (configModifier != null) {
            configModifier.apply(configBuilder);
        }

        final Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // add temp directory to config for marker files.
        configBuilder
                .withValue(
                        PathsConfig_.MARKER_FILES_DIR,
                        tmpDir.resolve(TEST_MARKER_FILE_DIRECTORY).toString())
                .withValue(PathsConfig_.WRITE_PLATFORM_MARKER_FILES, true);
        // add configuration to platform builder.
        platformContextBuilder.withConfiguration(configBuilder.getOrCreateConfig());
        if (platformContextModifier != null) {
            // apply any other modifications to the platform builder.
            platformContextModifier.apply(platformContextBuilder);
        }
        return platformContextBuilder.build();
    }

    @NonNull
    public static List<PlatformEvent> generateEvents(
            @NonNull final PlatformContext context,
            int numEvents,
            long seed,
            @NonNull final Roster roster,
            @NonNull final KeysAndCerts keysAndCerts) {
        List<PlatformEvent> events = new ArrayList<>(numEvents);

        final List<EventSource> sources = roster.rosterEntries().stream()
                .map(e -> (EventSource) new StandardEventSource(false))
                .toList();
        // Generate events using GraphGenerator with valid transactions
        final StandardGraphGenerator generator = new StandardGraphGenerator(
                context, seed,
                sources, roster);

        final var signer = new PlatformSigner(keysAndCerts);
        for (int i = 0; i < numEvents; i++) {
            // Generate event with hash but fake signature
            final PlatformEvent unsignedEvent = generator.generateEvent();

            // Sign the ACTUAL hash
            final Signature signature =
                    signer.sign(unsignedEvent.getHash().getBytes().toByteArray());

            // Create new GossipEvent with real signature using Builder
            final GossipEvent signedGossipEvent = new GossipEvent.Builder()
                    .eventCore(unsignedEvent.getGossipEvent().eventCore())
                    .signature(signature.getBytes()) // Real signature
                    .transactions(unsignedEvent.getGossipEvent().transactions()) // Transactions
                    .parents(unsignedEvent.getGossipEvent().parents()) // Parents
                    .build();

            // Create PlatformEvent from signed GossipEvent
            final PlatformEvent signedEvent = new PlatformEvent(signedGossipEvent);
            signedEvent.setHash(unsignedEvent.getHash()); // Preserve the hash

            events.add(signedEvent);
        }

        return events;
    }

    /**
     * Create a Roster for the given signers
     */
    @NonNull
    public static Roster generateRoster(@NonNull final Map<NodeId, KeysAndCerts> signers) {
        final List<RosterEntry> rosterEntries = new ArrayList<>();
        for (final Entry<NodeId, KeysAndCerts> signer : signers.entrySet()) {
            rosterEntries.add(createRosterEntry(signer.getKey(), signer.getValue()));
        }
        rosterEntries.sort(Comparator.comparingLong(RosterEntry::nodeId));
        return Roster.newBuilder().rosterEntries(rosterEntries).build();
    }

    @NonNull
    private static RosterEntry createRosterEntry(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        try {
            final long id = nodeId.id();
            final byte[] certificate = keysAndCerts.sigCert().getEncoded();
            return RosterEntry.newBuilder()
                    .nodeId(id)
                    .weight(500)
                    .gossipCaCertificate(Bytes.wrap(certificate))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .domainName(String.format("node-%d", id))
                            .port(8082)
                            .build())
                    .build();
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException("Exception while creating roster entry", e);
        }
    }
}
