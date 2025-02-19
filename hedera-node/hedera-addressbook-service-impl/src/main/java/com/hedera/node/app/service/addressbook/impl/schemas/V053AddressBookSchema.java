/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.addressbook.impl.schemas;

import static com.swirlds.common.utility.CommonUtils.unhex;
import static com.swirlds.platform.roster.RosterUtils.formatNodeName;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NodeAddress;
import com.hedera.hapi.node.base.NodeAddressBook;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
    public static final String ACCOUNTS_KEY = "ACCOUNTS";
    public static final String FILES_KEY = "FILES";
    public static final String NODES_KEY = "NODES";

    public V053AddressBookSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(NODES_KEY, EntityNumber.PROTOBUF, Node.PROTOBUF, MAX_NODES));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        requireNonNull(ctx);
        final var bootstrapConfig = ctx.appConfig().getConfigData(BootstrapConfig.class);
        // Since this schema's version is several releases behind the current version,
        // its migrate() will only be called at genesis in any case, but this makes it
        // explicit that the override admin keys apply only at genesis
        final Map<Long, Key> nodeAdminKeys =
                ctx.isGenesis() ? parseEd25519NodeAdminKeysFrom(bootstrapConfig.nodeAdminKeysPath()) : emptyMap();
        final var networkInfo = ctx.genesisNetworkInfo();
        if (networkInfo == null) {
            throw new IllegalStateException("Genesis network info is not found");
        }
        final WritableKVState<EntityNumber, Node> writableNodes =
                ctx.newStates().get(NODES_KEY);

        log.info("Started migrating nodes from address book");
        final var adminKey = getAccountAdminKey(ctx);
        final var nodeDetailMap = getNodeAddressMap(ctx);

        final var defaultAdminKey = adminKey == null || adminKey.equals(Key.DEFAULT)
                ? Key.newBuilder().ed25519(bootstrapConfig.genesisPublicKey()).build()
                : adminKey;
        NodeAddress nodeDetail;
        final var addressBook = networkInfo.addressBook();
        for (final var nodeInfo : addressBook) {
            final var nodeAdminKey = nodeAdminKeys.getOrDefault(nodeInfo.nodeId(), defaultAdminKey);
            if (nodeAdminKey != defaultAdminKey) {
                log.info("Override admin key for node{} is :: {}", nodeInfo.nodeId(), nodeAdminKey);
            }
            final var nodeBuilder = Node.newBuilder()
                    .nodeId(nodeInfo.nodeId())
                    .accountId(nodeInfo.accountId())
                    // Default node description hard coded to the values used currently
                    .description(formatNodeName(nodeInfo.nodeId()))
                    .gossipEndpoint(nodeInfo.gossipEndpoints())
                    .gossipCaCertificate(nodeInfo.sigCertBytes())
                    .weight(nodeInfo.weight())
                    .adminKey(nodeAdminKey);
            if (nodeDetailMap != null) {
                nodeDetail = nodeDetailMap.get(nodeInfo.nodeId());
                if (nodeDetail != null) {
                    final Bytes hashBytes =
                            Bytes.fromHex(nodeDetail.nodeCertHash().asUtf8String());
                    nodeBuilder.serviceEndpoint(nodeDetail.serviceEndpoint()).grpcCertificateHash(hashBytes);
                }
            }
            writableNodes.put(
                    EntityNumber.newBuilder().number(nodeInfo.nodeId()).build(), nodeBuilder.build());
        }

        log.info("Migrated {} nodes from address book", addressBook.size());
    }

    private Key getAccountAdminKey(@NonNull final MigrationContext ctx) {
        var adminKey = Key.DEFAULT;

        ReadableKVState<AccountID, Account> readableAccounts = null;

        try {
            readableAccounts = ctx.newStates().get(ACCOUNTS_KEY);
        } catch (IllegalArgumentException e) {
            log.info("AccountStore is not found, can be ignored.");
        }
        if (readableAccounts != null) {
            final var accountConfig = ctx.appConfig().getConfigData(AccountsConfig.class);
            final var adminAccount =
                    readableAccounts.get(ctx.entityIdFactory().newAccountId(accountConfig.addressBookAdmin()));
            if (adminAccount != null) {
                adminKey = adminAccount.keyOrElse(Key.DEFAULT);
            }
        }
        return adminKey;
    }

    private Map<Long, NodeAddress> getNodeAddressMap(@NonNull final MigrationContext ctx) {
        Map<Long, NodeAddress> nodeDetailMap = null;

        ReadableKVState<FileID, File> readableFiles = null;
        try {
            readableFiles = ctx.newStates().get(FILES_KEY);
        } catch (IllegalArgumentException e) {
            log.info("FileStore is not found, can be ignored.");
        }

        if (readableFiles != null) {
            final var fileConfig = ctx.appConfig().getConfigData(FilesConfig.class);
            final var nodeDetailFile = readableFiles.get(ctx.entityIdFactory().newFileId(fileConfig.nodeDetails()));

            if (nodeDetailFile != null) {
                try {
                    final var nodeDetails = NodeAddressBook.PROTOBUF
                            .parse(nodeDetailFile.contents())
                            .nodeAddress();
                    nodeDetailMap = nodeDetails.stream().collect(toMap(NodeAddress::nodeId, Function.identity()));
                } catch (ParseException e) {
                    log.warn("Can not parse file 102 ", e);
                }
            }
        }
        return nodeDetailMap;
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
    private static Map<Long, Key> parseEd25519NodeAdminKeysFrom(@NonNull final String loc) {
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
