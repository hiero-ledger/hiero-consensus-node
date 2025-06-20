// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.emergencyfile;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.swirlds.common.jackson.HashDeserializer;
import com.swirlds.common.jackson.InstantDeserializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.base.crypto.Hash;

/**
 * Data about the state written to disk, either during normal operation or at the end of event recovery.
 *
 * @param round
 * 		the round of the state. This value is required by the platform when reading a file.
 * @param hash
 * 		the hash of the state. This value is required by the platform when reading a file.
 * @param timestamp
 * 		the consensus timestamp of the state. This value is optional for the platform when reading a
 * 		file, but should always be populated with an accurate value when written by the platform.
 */
public record State(
        long round,
        @NonNull @JsonSerialize(using = ToStringSerializer.class) @JsonDeserialize(using = HashDeserializer.class)
                Hash hash,
        @NonNull @JsonSerialize(using = ToStringSerializer.class) @JsonDeserialize(using = InstantDeserializer.class)
                Instant timestamp) {}
