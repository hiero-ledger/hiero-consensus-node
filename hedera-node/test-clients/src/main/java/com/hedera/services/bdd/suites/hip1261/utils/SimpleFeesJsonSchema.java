// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A JSON-based simple fees schedule used for independent verification of simple fees calculations
 * outside the core Hedera FeeModelRegistry logic.
 * The structure mirrors the structure of the simple fees schedule JSON file used by the Simple Fees feature.
 * It can be deserialized from a JSON file, a classpath resource, or a raw JSON using Jackson.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SimpleFeesJsonSchema {
    /** Node-level base fee and included extras */
    public Node node;
    /** Network-level multiplier for the node fee */
    public Network network;
    /** Optional placeholder for non-parsable sections */
    public Unreadable unreadable;
    /** Global extras price table */
    public List<ExtraPrice> extras;
    /** List of services, each containing its own schedule of operations */
    public List<Service> services;

    /** Node section: defines base fee and included extras for node fee */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Node {
        public long baseFee;
        public List<Included> extras;
    }

    /** Network section: defines multiplier applied to node fee */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Network {
        public int multiplier;
    }

    /** Optional unreadable section */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Unreadable {
        public long unreadableFeeValue;
    }

    /** Global extra prices, mapping names like SIGNATURES -> fee value */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExtraPrice {
        public String name;
        public long fee;
    }

    /** Defines included (free) units of each extra, used at node or operation level */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Included {
        public String name;
        public long includedCount;
    }

    /** Describes a service, like Consensus or Token, with a list of operations */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Service {
        public String name;
        public List<ApiFee> schedule;
    }

    /** Describes a specific operation (API) within a service */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiFee {
        public String name;
        public long baseFee;
        public List<Included> extras;
    }
}
