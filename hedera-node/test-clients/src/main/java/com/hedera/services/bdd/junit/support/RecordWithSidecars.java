// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import com.hedera.services.stream.proto.RecordStreamFile;
import com.hedera.services.stream.proto.SidecarFile;
import java.util.List;

/**
 * Contains a single record stream file and a list of the sidecar files that include sidecars for
 * ANY record in the record stream file.
 */
public record RecordWithSidecars(RecordStreamFile recordFile, List<SidecarFile> sidecarFiles) {}
