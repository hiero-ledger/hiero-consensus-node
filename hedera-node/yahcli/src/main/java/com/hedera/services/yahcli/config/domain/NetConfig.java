// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.config.domain;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.props.NodeConnectInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Methods used by reflection to load java beans
@SuppressWarnings("unused")
public class NetConfig {
    private static final Integer TRADITIONAL_DEFAULT_NODE_ACCOUNT = 3;

    private String defaultPayer;
    private Integer defaultNodeAccount = TRADITIONAL_DEFAULT_NODE_ACCOUNT;
    private List<Long> allowedReceiverAccountIds;
    private List<NodeConfig> nodes;
    private Long shard;
    private Long realm;

    public String getDefaultPayer() {
        return defaultPayer;
    }

    public void setDefaultPayer(String defaultPayer) {
        this.defaultPayer = defaultPayer;
    }

    public Integer getDefaultNodeAccount() {
        return defaultNodeAccount;
    }

    public void setDefaultNodeAccount(Integer defaultNodeAccount) {
        this.defaultNodeAccount = defaultNodeAccount;
    }

    public List<NodeConfig> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeConfig> nodes) {
        this.nodes = nodes;
    }

    public List<Long> getAllowedReceiverAccountIds() {
        return allowedReceiverAccountIds;
    }

    public void setAllowedReceiverAccountIds(List<Long> allowedReceiverAccountIds) {
        this.allowedReceiverAccountIds = allowedReceiverAccountIds;
    }

    public Long getShard() {
        return shard != null ? shard : 0;
    }

    public void setShard(Long shard) {
        this.shard = shard;
    }

    public Long getRealm() {
        return realm != null ? realm : 0;
    }

    public void setRealm(Long realm) {
        this.realm = realm;
    }

    public Map<String, String> toSpecProperties() {
        Map<String, String> customProps = new HashMap<>();
        customProps.put("nodes", nodes.stream().map(NodeConfig::toString).collect(Collectors.joining(",")));
        if (shard != null) {
            customProps.put("hapi.spec.default.shard", String.valueOf(shard));
        }
        if (realm != null) {
            customProps.put("hapi.spec.default.realm", String.valueOf(realm));
        }
        return customProps;
    }

    public List<NodeConnectInfo> toNodeInfos() {
        Map<String, String> nodeInfos = new HashMap<>();
        return nodes.stream()
                .map(NodeConfig::toString)
                // Strip the node ID from the end of the string
                .map(s -> s.contains("#") ? s.substring(0, s.indexOf('#')) : s)
                .map(NodeConnectInfo::new)
                .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("defaultPayer", defaultPayer)
                .add("defaultNodeAccount", asEntityString(getShard(), getRealm(), defaultNodeAccount))
                .add("nodes", nodes)
                .add("allowedReceiverAccountIds", allowedReceiverAccountIds)
                .add("shard", shard)
                .add("realm", realm)
                .omitNullValues()
                .toString();
    }
}
