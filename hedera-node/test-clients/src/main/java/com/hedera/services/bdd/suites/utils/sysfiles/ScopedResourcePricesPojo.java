// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hederahashgraph.api.proto.java.SubType;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScopedResourcePricesPojo {
    SubType subType;

    ResourcePricesPojo nodedata, networkdata, servicedata;

    public SubType getSubType() {
        return subType;
    }

    public void setSubType(SubType subType) {
        this.subType = subType;
    }

    public ResourcePricesPojo getNodedata() {
        return nodedata;
    }

    public void setNodedata(ResourcePricesPojo nodedata) {
        this.nodedata = nodedata;
    }

    public ResourcePricesPojo getNetworkdata() {
        return networkdata;
    }

    public void setNetworkdata(ResourcePricesPojo networkdata) {
        this.networkdata = networkdata;
    }

    public ResourcePricesPojo getServicedata() {
        return servicedata;
    }

    public void setServicedata(ResourcePricesPojo servicedata) {
        this.servicedata = servicedata;
    }
}
