// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.testreader;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Various test metadata that is not parsed from results.
 *
 * @param owner    the team that owns this test, or "" if unknown
 * @param notesUrl the URL to the notes for this test, or "" if unknown
 */
public record JrsTestMetadata(@NonNull String owner, @NonNull String notesUrl) {}
