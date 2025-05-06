// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.emergencyfile;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.swirlds.common.jackson.HashDeserializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.URL;
import org.hiero.base.crypto.Hash;

/**
 * A location where a recovery package can be downloaded from
 * @param type the type of package file (e.g. "zip")
 * @param url the URL where the package can be downloaded from
 * @param hash the hash of the package file
 */
public record Location(
        @NonNull String type,
        @NonNull URL url,
        @NonNull @JsonSerialize(using = ToStringSerializer.class) @JsonDeserialize(using = HashDeserializer.class)
                Hash hash) {}
