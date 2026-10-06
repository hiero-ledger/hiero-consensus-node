// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.clpr;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hederahashgraph.api.proto.java.ClprEndpointManifest;
import com.hederahashgraph.api.proto.java.ClprGetEndpointManifestQuery;
import com.hederahashgraph.api.proto.java.ClprGetEndpointManifestResponse;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * HAPI spec operation for {@code ClprGetEndpointManifest} free queries (spec §6.5).
 *
 * <p>Usage:
 * <pre>
 *   clprGetEndpointManifest()
 *       .payingWith(GENESIS)
 *       .exposingManifestTo(m -&gt; localManifestVersion.set(m.getVersion()))
 * </pre>
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class HapiClprGetEndpointManifest extends HapiQueryOp<HapiClprGetEndpointManifest> {

    private Consumer<ClprEndpointManifest> manifestObserver = m -> {};
    private Consumer<ByteString> proofObserver = p -> {};

    private Optional<Long> expectedMinVersion = Optional.empty();
    private Optional<Integer> expectedMinEndpoints = Optional.empty();

    public HapiClprGetEndpointManifest() {}

    /**
     * Expose the parsed manifest to a consumer for ad-hoc assertions.
     */
    public HapiClprGetEndpointManifest exposingManifestTo(final Consumer<ClprEndpointManifest> observer) {
        this.manifestObserver = observer;
        return this;
    }

    /**
     * Expose the serialized {@code manifest_state_proof} bytes to a consumer. Empty when the
     * peer hasn't yet produced a signed block snapshot.
     */
    public HapiClprGetEndpointManifest exposingProofTo(final Consumer<ByteString> observer) {
        this.proofObserver = observer;
        return this;
    }

    /**
     * Assert {@code manifest.version() >= min} — non-zero implies the reconciler has closed at
     * least one construction and finalized a manifest (v=0 is the genesis placeholder).
     */
    public HapiClprGetEndpointManifest hasVersionAtLeast(final long min) {
        this.expectedMinVersion = Optional.of(min);
        return this;
    }

    /**
     * Assert {@code manifest.endpoints_count >= min}.
     */
    public HapiClprGetEndpointManifest hasEndpointsAtLeast(final int min) {
        this.expectedMinEndpoints = Optional.of(min);
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ClprGetEndpointManifest;
    }

    @Override
    protected HapiClprGetEndpointManifest self() {
        return this;
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        final var inner = ClprGetEndpointManifestQuery.newBuilder()
                .setHeader(responseType == ResponseType.COST_ANSWER ? answerCostHeader(payment) : answerHeader(payment))
                .build();
        return Query.newBuilder().setClprGetEndpointManifest(inner).build();
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        final var inner = manifestResponse();
        if (inner.hasManifest()) {
            manifestObserver.accept(inner.getManifest());
        }
        proofObserver.accept(inner.getManifestStateProof());
    }

    @Override
    @SuppressWarnings("java:S5960")
    protected void assertExpectationsGiven(final HapiSpec spec) {
        final var manifest = manifestResponse().getManifest();
        assertNotNull(manifest, "Manifest must not be null");

        expectedMinVersion.ifPresent(min -> assertTrue(
                manifest.getVersion() >= min,
                "Expected manifest.version >= " + min + ", got " + manifest.getVersion()));
        expectedMinEndpoints.ifPresent(min -> assertEquals(
                true,
                manifest.getEndpointsCount() >= min,
                "Expected manifest.endpoints_count >= " + min + ", got " + manifest.getEndpointsCount()));
    }

    private ClprGetEndpointManifestResponse manifestResponse() {
        return response.getClprGetEndpointManifest();
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("query", "ClprGetEndpointManifest");
    }
}
