// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.LIVE_HASH_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNKNOWN;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.stream.Stream;

public interface OpProvider {
    ResponseCodeEnum[] STANDARD_PERMISSIBLE_QUERY_PRECHECKS = {
        OK, BUSY, INSUFFICIENT_TX_FEE, PLATFORM_TRANSACTION_NOT_CREATED, TRANSACTION_EXPIRED
    };

    ResponseCodeEnum[] STANDARD_PERMISSIBLE_PRECHECKS = {
        OK,
        BUSY,
        DUPLICATE_TRANSACTION,
        INVALID_PAYER_SIGNATURE,
        INVALID_SIGNATURE,
        INSUFFICIENT_PAYER_BALANCE,
        PAYER_ACCOUNT_DELETED,
        PLATFORM_TRANSACTION_NOT_CREATED
    };

    ResponseCodeEnum[] STANDARD_PERMISSIBLE_OUTCOMES = {
        SUCCESS,
        LIVE_HASH_NOT_FOUND,
        INSUFFICIENT_PAYER_BALANCE,
        UNKNOWN,
        INSUFFICIENT_TX_FEE,
        INVALID_SIGNATURE,
        PAYER_ACCOUNT_DELETED
    };

    default List<SpecOperation> suggestedInitializers() {
        return Collections.emptyList();
    }

    Optional<HapiSpecOperation> get();

    default ResponseCodeEnum[] standardQueryPrechecksAnd(ResponseCodeEnum... more) {
        return plus(STANDARD_PERMISSIBLE_QUERY_PRECHECKS, more);
    }

    default ResponseCodeEnum[] standardPrechecksAnd(ResponseCodeEnum... more) {
        return plus(STANDARD_PERMISSIBLE_PRECHECKS, more);
    }

    default ResponseCodeEnum[] standardOutcomesAnd(ResponseCodeEnum... more) {
        return plus(STANDARD_PERMISSIBLE_OUTCOMES, more);
    }

    default ResponseCodeEnum[] plus(ResponseCodeEnum[] some, ResponseCodeEnum[] more) {
        return Stream.concat(Stream.of(some), Stream.of(more)).toArray(n -> new ResponseCodeEnum[n]);
    }

    default String unique(String opName, Class<?> providerType) {
        return opName + "-" + providerType.getSimpleName();
    }

    String UNIQUE_PAYER_ACCOUNT = "uniquePayerAccount";
    long UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE = 500_000_000_000L;
    long TRANSACTION_FEE = 50_000_000_000L;
    SplittableRandom BASE_RANDOM = new SplittableRandom();
}
