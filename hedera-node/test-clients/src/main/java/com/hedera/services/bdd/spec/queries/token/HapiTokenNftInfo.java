// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.token;

import com.google.protobuf.ByteString;
import java.util.Optional;
import java.util.OptionalLong;

public class HapiTokenNftInfo {
    private OptionalLong expectedSerialNum;
    private Optional<ByteString> expectedMetadata;
    private Optional<String> expectedTokenID;
    private Optional<String> expectedAccountID;
    private Optional<String> expectedLedgerID;

    private HapiTokenNftInfo(
            Optional<String> tokenId,
            OptionalLong serialNum,
            Optional<String> accountId,
            Optional<ByteString> metadata,
            Optional<String> ledgerId) {
        this.expectedSerialNum = serialNum;
        this.expectedMetadata = metadata;
        this.expectedTokenID = tokenId;
        this.expectedAccountID = accountId;
        this.expectedLedgerID = ledgerId;
    }

    public static HapiTokenNftInfo newTokenNftInfo(
            final String tokenId,
            final long serialNum,
            final String accountId,
            final ByteString metadata,
            final String ledgerId) {
        return new HapiTokenNftInfo(
                Optional.of(tokenId),
                OptionalLong.of(serialNum),
                Optional.of(accountId),
                Optional.of(metadata),
                Optional.of(ledgerId));
    }

    public static HapiTokenNftInfo newTokenNftInfo(
            final String tokenId, final long serialNum, final String accountId, final ByteString metadata) {
        return newTokenNftInfo(tokenId, serialNum, accountId, metadata, "0x03");
    }

    public OptionalLong getExpectedSerialNum() {
        return expectedSerialNum;
    }

    public Optional<ByteString> getExpectedMetadata() {
        return expectedMetadata;
    }

    public Optional<String> getExpectedTokenID() {
        return expectedTokenID;
    }

    public Optional<String> getExpectedAccountID() {
        return expectedAccountID;
    }

    public Optional<String> getExpectedLedgerID() {
        return expectedLedgerID;
    }
}
