// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.meta;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.NetworkGetVersionInfoQuery;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetVersionInfo extends HapiQueryOp<HapiGetVersionInfo> {
    private static final Logger LOG = LogManager.getLogger(HapiGetVersionInfo.class);

    private boolean assertNoDegenSemvers = false;
    private Optional<SemanticVersion> expectedProto = Optional.empty();
    private Optional<SemanticVersion> expectedServices = Optional.empty();
    private String servicesSemVerBuild = "";

    @Nullable
    private Consumer<SemanticVersion> servicesVersionConsumer;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.GetVersionInfo;
    }

    @Override
    protected HapiGetVersionInfo self() {
        return this;
    }

    public HapiGetVersionInfo hasProtoSemVer(SemanticVersion sv) {
        expectedProto = Optional.of(sv);
        return this;
    }

    public HapiGetVersionInfo exposingServicesVersionTo(@NonNull final Consumer<SemanticVersion> consumer) {
        servicesVersionConsumer = Objects.requireNonNull(consumer);
        return this;
    }

    public HapiGetVersionInfo hasProtoServicesVersion(SemanticVersion version) {
        expectedServices = Optional.of(version);
        return this;
    }

    public HapiGetVersionInfo hasServicesVersion(com.hedera.hapi.node.base.SemanticVersion version) {
        expectedServices = Optional.of(
                pbjToProto(version, com.hedera.hapi.node.base.SemanticVersion.class, SemanticVersion.class));
        return this;
    }

    public HapiGetVersionInfo hasServicesSemVer(SemanticVersion sv) {
        expectedServices = Optional.of(sv);
        return this;
    }

    public HapiGetVersionInfo hasNoDegenerateSemvers() {
        assertNoDegenSemvers = true;
        return this;
    }

    public HapiGetVersionInfo hasServicesSemVerBuild(final String build) {
        servicesSemVerBuild = build;
        return this;
    }

    @Override
    @SuppressWarnings("java:S5960")
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        SemanticVersion actualProto = response.getNetworkGetVersionInfo().getHapiProtoVersion();
        SemanticVersion actualServices = response.getNetworkGetVersionInfo().getHederaServicesVersion();
        if (servicesVersionConsumer != null) {
            servicesVersionConsumer.accept(actualServices);
        }
        if (expectedProto.isPresent()) {
            Assertions.assertEquals(expectedProto.get(), actualProto, "Wrong HAPI proto version");
        }
        if (expectedServices.isPresent()) {
            Assertions.assertEquals(expectedServices.get(), actualServices, "Wrong Hedera Services version");
        }
        if (assertNoDegenSemvers) {
            var degenSemver = SemanticVersion.getDefaultInstance();
            Assertions.assertNotEquals(degenSemver, actualProto);
            Assertions.assertNotEquals(degenSemver, actualServices);
        }
        if (!servicesSemVerBuild.isEmpty()) {
            Assertions.assertEquals(servicesSemVerBuild, actualServices.getBuild());
        }
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        var info = response.getNetworkGetVersionInfo();
        if (verboseLoggingOn) {
            LOG.info(
                    "Versions :: HAPI protobufs @ {}, Hedera Services @ {}",
                    () -> asReadable(info.getHapiProtoVersion()),
                    () -> asReadable(info.getHederaServicesVersion()));
        }

        if (yahcliLogger) {
            System.out.println(".i. "
                    + String.format(
                            "Versions :: HAPI protobufs @ %s, Hedera Services @ %s",
                            asReadable(info.getHapiProtoVersion()), asReadable(info.getHederaServicesVersion())));
        }
    }

    private String asReadable(SemanticVersion semver) {
        var sb = new StringBuilder()
                .append(semver.getMajor())
                .append(".")
                .append(semver.getMinor())
                .append(".")
                .append(semver.getPatch());
        var preRelease = semver.getPre();
        if (!preRelease.isBlank()) {
            sb.append("-").append(preRelease);
        }

        var buildMeta = semver.getBuild();
        if (!buildMeta.isBlank()) {
            sb.append("+").append(buildMeta);
        }

        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getVersionInfoQuery(payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getVersionInfoQuery(Transaction payment, boolean costOnly) {
        NetworkGetVersionInfoQuery getVersionQuery = NetworkGetVersionInfoQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .build();
        return Query.newBuilder().setNetworkGetVersionInfo(getVersionQuery).build();
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this);
    }
}
