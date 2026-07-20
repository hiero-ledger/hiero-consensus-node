// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

/**
 * Three-valued result of a deterministic check. Only a certain {@link #ABSENT} may assert drift;
 * {@link #UNVERIFIABLE} is routed to the quiet log and never to the report.
 */
public enum Outcome {
    /** The cited thing was found — no drift. */
    PRESENT,
    /** The cited thing is certainly gone, with verifiable evidence — assert drift. */
    ABSENT,
    /** The check could not be decided as a fact (generated/external symbol, ambiguous). Stay quiet. */
    UNVERIFIABLE
}
