// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.domain;

import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Base64;
import java.util.Objects;

/**
 * A POJO for Jackson to use in storing a Base64-encoded {@code (TransactionBody, TransactionRecord)} pair.
 */
public class EncodedItem {
    private String b64Body;
    private String b64Record;

    public static EncodedItem fromParsed(
            @NonNull final TransactionBody itemBody, @NonNull final TransactionRecord itemRecord) {
        Objects.requireNonNull(itemBody);
        Objects.requireNonNull(itemRecord);
        final var item = new EncodedItem();
        item.setB64Body(Base64.getEncoder().encodeToString(itemBody.toByteArray()));
        item.setB64Record(Base64.getEncoder().encodeToString(itemRecord.toByteArray()));
        return item;
    }

    public String getB64Body() {
        return b64Body;
    }

    public void setB64Body(@NonNull final String b64Body) {
        this.b64Body = Objects.requireNonNull(b64Body);
    }

    public String getB64Record() {
        return b64Record;
    }

    public void setB64Record(@NonNull final String b64Record) {
        this.b64Record = Objects.requireNonNull(b64Record);
    }
}
