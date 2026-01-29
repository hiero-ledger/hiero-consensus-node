// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RosterWithKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.params.provider.Arguments;

/**
 * This class is used for generating unit test method parameters, even though IntelliJ says it is not used.
 */
public class CryptoArgsProvider {
    public static final int NUMBER_OF_ADDRESSES = 10;
    private static final char[] PASSWORD = "password".toCharArray();

    /**
     * @return 2 sets of arguments, 1 generated, 1 loaded from files.
     */
    static Stream<Arguments> basicTestArgs() throws Exception {
        final RosterAndCerts rosterAndCerts = genRosterLoadKeys(NUMBER_OF_ADDRESSES);
        final RosterWithKeys rosterWithKeys = RandomRosterBuilder.create(Randotron.create())
                .withSize(NUMBER_OF_ADDRESSES)
                .withRealKeysEnabled(true)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                .buildWithKeys();
        final Map<NodeId, KeysAndCerts> genKac = rosterWithKeys.getRoster().rosterEntries().stream()
                .map(RosterEntry::nodeId)
                .map(NodeId::of)
                .collect(Collectors.toMap(Function.identity(), rosterWithKeys::getKeysAndCerts));
        return Stream.of(
                Arguments.of(rosterAndCerts.roster(), rosterAndCerts.nodeIdKeysAndCertsMap()),
                Arguments.of(rosterWithKeys.getRoster(), genKac));
    }

    private static Configuration configure(final Path keyDirectory) {
        final ConfigurationBuilder builder = ConfigurationBuilder.create();

        builder.withConfigDataTypes(PathsConfig.class, CryptoConfig.class);

        builder.withValue("paths.keysDirPath", keyDirectory.toAbsolutePath().toString());
        builder.withValue("crypto.password", new String(PASSWORD));

        return builder.build();
    }

    /**
     * Creates a roster with keys loaded from pre-generated PEM files.
     *
     * @param size the size of the required roster
     */
    @NonNull
    public static RosterAndCerts genRosterLoadKeys(final int size)
            throws URISyntaxException, KeyLoadingException, KeyStoreException, NoSuchAlgorithmException,
                    KeyGeneratingException, NoSuchProviderException, CertificateEncodingException {
        final Roster createdAB = RandomRosterBuilder.create(Randotron.create())
                .withSize(size)
                .withRealKeysEnabled(false)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                .build();
        final Set<NodeId> nodeIds = createdAB.rosterEntries().stream()
                .map(r -> NodeId.of(r.nodeId()))
                .collect(Collectors.toSet());
        final Map<NodeId, KeysAndCerts> loadedC = EnhancedKeyStoreLoader.using(
                        configure(ResourceLoader.getFile("preGeneratedPEMKeysAndCerts/")), nodeIds)
                .scan()
                .generate()
                .verify()
                .keysAndCerts();
        final ArrayList<RosterEntry> rosterEntries = new ArrayList<>();
        for (final RosterEntry entry : createdAB.rosterEntries()) {
            final RosterEntry newOne = entry.copyBuilder()
                    .gossipCaCertificate(Bytes.wrap(
                            loadedC.get(NodeId.of(entry.nodeId())).sigCert().getEncoded()))
                    .build();
            rosterEntries.add(newOne);
        }
        return new RosterAndCerts(new Roster(rosterEntries), loadedC);
    }
}
