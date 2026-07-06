// SPDX-License-Identifier: Apache-2.0
package com.hedera.kbfreshness.findings;

import com.hedera.kbfreshness.model.Triage;

/**
 * One row of the human-owned baseline.
 *
 * @param id        the finding identity.
 * @param triage    the curator's disposition.
 * @param firstSeen the run date this finding was first recorded (free-form; may be empty).
 * @param note      an optional curator note.
 */
public record BaselineEntry(String id, Triage triage, String firstSeen, String note) {}
