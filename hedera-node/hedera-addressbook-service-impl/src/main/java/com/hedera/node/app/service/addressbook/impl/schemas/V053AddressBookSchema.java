// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.schemas;

import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static org.hiero.consensus.model.utility.CommonUtils.unhex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Genesis schema of the address book service.
 */
public class V053AddressBookSchema extends Schema {
    private static final Logger log = LogManager.getLogger(V053AddressBookSchema.class);
    private static final Pattern IPV4_ADDRESS_PATTERN =
            Pattern.compile("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$");

    private static final long MAX_NODES = 100L;
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(53).patch(0).build();
    public static final String NODES_KEY = "NODES";

    public V053AddressBookSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(NODES_KEY, EntityNumber.PROTOBUF, Node.PROTOBUF, MAX_NODES));
    }

    /**
     * Given a host and port, creates a {@link ServiceEndpoint} object with either an IP address or domain name
     * depending on the given host.
     *
     * @param host the host
     * @param port the port
     * @return the {@link ServiceEndpoint} object
     */
    public static ServiceEndpoint endpointFor(@NonNull final String host, final int port) {
        final var builder = ServiceEndpoint.newBuilder().port(port);
        if (IPV4_ADDRESS_PATTERN.matcher(host).matches()) {
            final var octets = host.split("[.]");
            builder.ipAddressV4(Bytes.wrap(new byte[] {
                (byte) Integer.parseInt(octets[0]),
                (byte) Integer.parseInt(octets[1]),
                (byte) Integer.parseInt(octets[2]),
                (byte) Integer.parseInt(octets[3])
            }));
        } else {
            builder.domainName(host);
        }
        return builder.build();
    }

    /**
     * Parses the given JSON file as a map from node ids to hexed Ed25519 public keys.
     * @param loc the location of the JSON file
     * @return the map from node ids to Ed25519 keys
     */
    public static Map<Long, Key> parseEd25519NodeAdminKeysFrom(@NonNull final String loc) {
        final var path = Paths.get(loc);
        try {
            final var json = Files.readString(path);
            return parseEd25519NodeAdminKeys(json);
        } catch (IOException ignore) {
            return emptyMap();
        }
    }

    /**
     * Parses the given JSON string as a map from node ids to hexed Ed25519 public keys.
     * @param json the JSON string
     * @return the map from node ids to Ed25519 keys
     */
    public static Map<Long, Key> parseEd25519NodeAdminKeys(@NonNull final String json) {
        requireNonNull(json);
        final var mapper = new ObjectMapper();
        try {
            final Map<Long, String> result = mapper.readValue(json, new TypeReference<>() {});
            return result.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> Key.newBuilder()
                    .ed25519(Bytes.wrap(unhex(e.getValue())))
                    .build()));
        } catch (JsonProcessingException e) {
            log.warn("Unable to parse override keys", e);
            return emptyMap();
        }
    }
}
