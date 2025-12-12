// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Instant start = Instant.now();
        final RosterAndCerts rosterAndCerts = loadAddressBookWithKeys(NUMBER_OF_ADDRESSES);
        start = Instant.now();
        final Roster genAB = createAddressBook(NUMBER_OF_ADDRESSES);
        final List<NodeId> nodeIds =
                genAB.rosterEntries().stream().map(r -> NodeId.of(r.nodeId())).toList();
        final Map<NodeId, KeysAndCerts> genC = CryptoStatic.generateKeysAndCerts(nodeIds);
        start = Instant.now();
        return Stream.of(
                Arguments.of(rosterAndCerts.roster(), rosterAndCerts.nodeIdKeysAndCertsMap()),
                Arguments.of(genAB, genC));
    }

    public static Roster createAddressBook(final int size) {
        return RandomRosterBuilder.create(Randotron.create())
                .withSize(size)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                .build();
    }

    private static Configuration configure(final Path keyDirectory) {
        final ConfigurationBuilder builder = ConfigurationBuilder.create();

        builder.withConfigDataTypes(PathsConfig.class, CryptoConfig.class);

        builder.withValue("paths.keysDirPath", keyDirectory.toAbsolutePath().toString());
        builder.withValue("crypto.password", new String(PASSWORD));

        return builder.build();
    }

    /**
     * returns a record with the addressBook and keys loaded from file.
     *
     * @param size the size of the required address book
     */
    @NonNull
    public static RosterAndCerts loadAddressBookWithKeys(final int size)
            throws URISyntaxException, KeyLoadingException, KeyStoreException, NoSuchAlgorithmException,
                    KeyGeneratingException, NoSuchProviderException {
        final Roster createdAB = createAddressBook(size);
        final Set<NodeId> nodeIds = createdAB.rosterEntries().stream()
                .map(r -> NodeId.of(r.nodeId()))
                .collect(Collectors.toSet());
        final Map<NodeId, KeysAndCerts> loadedC = EnhancedKeyStoreLoader.using(
                        nodeIds, configure(ResourceLoader.getFile("preGeneratedPEMKeysAndCerts/")), nodeIds)
                .scan()
                .generate()
                .verify()
                .keysAndCerts();
        return new RosterAndCerts(createdAB, loadedC);
    }
}
