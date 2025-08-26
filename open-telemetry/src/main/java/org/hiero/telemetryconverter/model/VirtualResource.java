package org.hiero.telemetryconverter.model;

import io.opentelemetry.pbj.common.v1.AnyValue;
import io.opentelemetry.pbj.common.v1.KeyValue;
import io.opentelemetry.pbj.resource.v1.Resource;

/**
 * Virtual resources representing different subsystems of the platform for telemetry purposes.
 */
public enum VirtualResource {
    INGESTION("hiero-cn-ingestion"),
    EVENT_CREATION("hiero-cn-event-creation"),
    GOSSIP("hiero-cn-gossip"),
    CONSENSUS("hiero-cn-consensus"),
    EXECUTION("hiero-cn-execution"),
    BLOCK("hiero-cn-block");
    public final Resource resource;

    VirtualResource(String serviceName) {
        this.resource = Resource.newBuilder()
                .attributes(new KeyValue("service.name", AnyValue.newBuilder().stringValue(serviceName).build()))
                .build();
    }
}