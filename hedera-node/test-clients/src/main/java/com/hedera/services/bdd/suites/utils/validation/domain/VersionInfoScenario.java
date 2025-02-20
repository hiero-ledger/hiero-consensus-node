// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.validation.domain;

public class VersionInfoScenario {
    private static final String CURRENT_SERVICES_SEMVER = "0.5.8";
    private static final String CURRENT_HAPI_PROTO_SEMVER = "0.5.8";

    String servicesSemVer = CURRENT_SERVICES_SEMVER;
    String hapiProtoSemVer = CURRENT_HAPI_PROTO_SEMVER;

    public String getServicesSemVer() {
        return servicesSemVer;
    }

    public void setServicesSemVer(String servicesSemVer) {
        this.servicesSemVer = servicesSemVer;
    }

    public String getHapiProtoSemVer() {
        return hapiProtoSemVer;
    }

    public void setHapiProtoSemVer(String hapiProtoSemVer) {
        this.hapiProtoSemVer = hapiProtoSemVer;
    }
}
