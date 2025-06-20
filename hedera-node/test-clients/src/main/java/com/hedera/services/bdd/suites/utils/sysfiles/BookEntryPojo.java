// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hederahashgraph.api.proto.java.NodeAddress;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookEntryPojo {
    private static final String MISSING_CERT_HASH = "<N/A>";
    private static final String SENTINEL_REPLACEMENT_VALUE = "!";

    public static class EndpointPojo {
        private String ipAddressV4;
        private Integer port;

        @SuppressWarnings("java:S5960")
        private static String asReadableIp(final ByteString octets) {
            final byte[] raw = octets.toByteArray();
            final var sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append("" + (0xff & raw[i]));
                if (i != 3) {
                    sb.append(".");
                }
            }
            return sb.toString();
        }

        public String getIpAddressV4() {
            return ipAddressV4;
        }

        public void setIpAddressV4(final String ipAddressV4) {
            this.ipAddressV4 = ipAddressV4;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(final Integer port) {
            this.port = port;
        }

        @SuppressWarnings("java:S5960")
        static EndpointPojo fromGrpc(final ServiceEndpoint proto) {
            final var pojo = new EndpointPojo();
            pojo.setIpAddressV4(asReadableIp(proto.getIpAddressV4()));
            pojo.setPort(proto.getPort());
            Assertions.assertNotEquals(0, pojo.getPort().intValue(), "A port is a positive integer!");
            return pojo;
        }

        ServiceEndpoint toGrpc() {
            return ServiceEndpoint.newBuilder()
                    .setIpAddressV4(asOctets(ipAddressV4))
                    .setPort(port)
                    .build();
        }
    }

    private String deprecatedIp;
    private String deprecatedMemo;
    private Integer deprecatedPortNo;

    private Long stake;
    private Long nodeId;
    private String certHash = MISSING_CERT_HASH;
    private String rsaPubKey;
    private String nodeAccount;
    private String description;
    private List<EndpointPojo> endpoints;

    @SuppressWarnings("java:S1874")
    static BookEntryPojo fromGrpc(final NodeAddress address) {
        final var entry = new BookEntryPojo();

        entry.deprecatedIp =
                address.getIpAddress().isEmpty() ? null : address.getIpAddress().toStringUtf8();
        entry.deprecatedPortNo = address.getPortno();
        if (entry.deprecatedPortNo == 0) {
            entry.deprecatedPortNo = null;
        }
        entry.deprecatedMemo =
                address.getMemo().isEmpty() ? null : address.getMemo().toStringUtf8();

        entry.rsaPubKey = address.getRSAPubKey().isEmpty() ? null : address.getRSAPubKey();
        entry.nodeId = address.getNodeId();
        if (address.hasNodeAccountId()) {
            entry.nodeAccount = HapiPropertySource.asAccountString(address.getNodeAccountId());
        } else {
            try {
                final var memo = address.getMemo().toStringUtf8();
                HapiPropertySource.asAccount(memo);
                entry.nodeAccount = memo;
            } catch (final Exception ignore) {
                entry.nodeAccount = null;
            }
        }
        entry.certHash = address.getNodeCertHash().isEmpty()
                ? MISSING_CERT_HASH
                : address.getNodeCertHash().toStringUtf8();
        mapEndpoints(address, entry);

        entry.description = address.getDescription().isEmpty() ? null : address.getDescription();
        entry.stake = address.getStake();
        if (entry.stake == 0) {
            entry.stake = null;
        }

        return entry;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @SuppressWarnings("java:S1874")
    public Stream<NodeAddress> toGrpcStream(@Nullable final String srcDir) {
        final var grpc = NodeAddress.newBuilder();

        if (deprecatedIp != null) {
            grpc.setIpAddress(ByteString.copyFromUtf8(deprecatedIp));
        }
        grpc.setPortno(Optional.ofNullable(deprecatedPortNo).orElse(0));
        if (deprecatedMemo != null) {
            grpc.setMemo(ByteString.copyFromUtf8(deprecatedMemo));
        }
        grpc.setNodeId(Optional.ofNullable(nodeId).orElse(0L));

        if (rsaPubKey != null) {
            if (rsaPubKey.equals(SENTINEL_REPLACEMENT_VALUE)) {
                if (srcDir == null) {
                    throw new IllegalStateException("No source directory given for RSA pub key replacement");
                }
                final var baseDir = srcDir + File.separator + "pubkeys";
                final var computedKey = asHexEncodedDerPubKey(baseDir, grpc.getNodeId());
                grpc.setRSAPubKey(computedKey);
            } else {
                grpc.setRSAPubKey(rsaPubKey);
            }
        }
        if (nodeAccount != null) {
            grpc.setNodeAccountId(HapiPropertySource.asAccount(nodeAccount));
        }
        if (!certHash.equals(MISSING_CERT_HASH)) {
            if (certHash.equals(SENTINEL_REPLACEMENT_VALUE)) {
                if (srcDir == null) {
                    throw new IllegalStateException("No source directory given for RSA cert replacement");
                }
                final var baseDir = srcDir + File.separator + "certs";
                final var computedHash = asHexEncodedSha384HashFor(baseDir, grpc.getNodeId());
                grpc.setNodeCertHash(ByteString.copyFromUtf8(computedHash));
            } else {
                grpc.setNodeCertHash(ByteString.copyFromUtf8(certHash));
            }
        }

        for (final var endpoint : endpoints) {
            grpc.addServiceEndpoint(endpoint.toGrpc());
        }
        if (description != null) {
            grpc.setDescription(description);
        }
        if (stake != null) {
            grpc.setStake(stake);
        }

        return Stream.of(grpc.build());
    }

    private static void mapEndpoints(final NodeAddress from, final BookEntryPojo to) {
        to.endpoints = from.getServiceEndpointList().stream()
                .map(EndpointPojo::fromGrpc)
                .toList();
    }

    public Long getStake() {
        return stake;
    }

    public void setStake(final Long stake) {
        this.stake = stake;
    }

    public String getDeprecatedIp() {
        return deprecatedIp;
    }

    public void setDeprecatedIp(final String deprecatedIp) {
        this.deprecatedIp = deprecatedIp;
    }

    public String getDeprecatedMemo() {
        return deprecatedMemo;
    }

    public void setDeprecatedMemo(final String deprecatedMemo) {
        this.deprecatedMemo = deprecatedMemo;
    }

    public String getNodeAccount() {
        return nodeAccount;
    }

    public void setNodeAccount(final String nodeAccount) {
        this.nodeAccount = nodeAccount;
    }

    public String getRsaPubKey() {
        return rsaPubKey;
    }

    public void setRsaPubKey(final String rsaPubKey) {
        this.rsaPubKey = rsaPubKey;
    }

    public String getCertHash() {
        return certHash;
    }

    public void setCertHash(final String certHash) {
        this.certHash = certHash;
    }

    public Integer getDeprecatedPortNo() {
        return deprecatedPortNo;
    }

    public void setDeprecatedPortNo(final Integer deprecatedPortNo) {
        this.deprecatedPortNo = deprecatedPortNo;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(final Long nodeId) {
        this.nodeId = nodeId;
    }

    public List<EndpointPojo> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(final List<EndpointPojo> endpoints) {
        this.endpoints = endpoints;
    }

    static String asHexEncodedSha384HashFor(final String baseDir, final long nodeId) {
        try {
            final var crtBytes = Files.readAllBytes(Paths.get(baseDir, String.format("node%d.crt", nodeId)));
            final var crtHash = CommonUtils.noThrowSha384HashOf(crtBytes);
            return org.hiero.base.utility.CommonUtils.hex(crtHash);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String asHexEncodedDerPubKey(final String baseDir, final long nodeId) {
        try {
            final var pubKeyBytes = Files.readAllBytes(Paths.get(baseDir, String.format("node%d.der", nodeId)));
            return org.hiero.base.utility.CommonUtils.hex(pubKeyBytes);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ByteString asOctets(final String ipAddressV4) {
        final byte[] octets = new byte[4];
        final String[] literals = ipAddressV4.split("[.]");
        for (int i = 0; i < 4; i++) {
            octets[i] = (byte) Integer.parseInt(literals[i]);
        }
        return ByteString.copyFrom(octets);
    }
}
