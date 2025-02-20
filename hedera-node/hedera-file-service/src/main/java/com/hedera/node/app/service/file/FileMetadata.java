// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * File metadata.
 *
 * @param fileId                   file's ID
 * @param expirationTimestamp      file's expiration time
 * @param keys                     file's sign keys
 * @param contents                 file bytes that are the contents of the file
 * @param memo                     file's memo
 * @param deleted                  file's deleted flag
 */
public record FileMetadata(
        FileID fileId,
        @Nullable Timestamp expirationTimestamp,
        @NonNull KeyList keys,
        @NonNull Bytes contents,
        @Nullable String memo,
        boolean deleted,
        @Nullable Timestamp preSystemDeleteExpirationSecond) {}
