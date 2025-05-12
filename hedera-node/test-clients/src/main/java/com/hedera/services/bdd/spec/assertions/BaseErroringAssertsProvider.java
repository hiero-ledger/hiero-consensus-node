// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isIdLiteral;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isNumericLiteral;

import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;

public class BaseErroringAssertsProvider<T> implements ErroringAssertsProvider<T> {
    List<Function<HapiSpec, Function<T, Optional<Throwable>>>> testProviders = new ArrayList<>();

    protected void registerProvider(AssertUtils.ThrowingAssert throwing) {
        testProviders.add(spec -> instance -> {
            try {
                throwing.assertThrowable(spec, instance);
            } catch (Throwable t) {
                return Optional.of(t);
            }
            return Optional.empty();
        });
    }

    /* Helper for asserting something about a ContractID, FileID, AccountID, etc. */
    @SuppressWarnings("unchecked")
    protected <R> void registerIdLookupAssert(String key, Function<T, R> getActual, Class<R> cls, String err) {
        registerProvider((spec, o) -> {
            final var keyToUse =
                    isNumericLiteral(key) ? asEntityString(spec.shard(), spec.realm(), Long.parseLong(key)) : key;
            R expected = isIdLiteral(keyToUse)
                    ? parseIdByType(keyToUse, cls)
                    : spec.registry().getId(keyToUse, cls);
            R actual = getActual.apply((T) o);
            Assertions.assertEquals(expected, actual, err);
        });
    }

    @SuppressWarnings("unchecked")
    private static <R> R parseIdByType(final String literal, Class<R> cls) {
        if (cls.equals(AccountID.class)) {
            return (R) HapiPropertySource.asAccount(literal);
        } else if (cls.equals(ContractID.class)) {
            return (R) HapiPropertySource.asContract(literal);
        } else if (cls.equals(TokenID.class)) {
            return (R) HapiPropertySource.asToken(literal);
        } else if (cls.equals(FileID.class)) {
            return (R) HapiPropertySource.asFile(literal);
        } else if (cls.equals(TopicID.class)) {
            return (R) HapiPropertySource.asTopic(literal);
        } else {
            throw new IllegalArgumentException("Cannot parse an id of type " + cls.getSimpleName());
        }
    }

    @Override
    public ErroringAsserts<T> assertsFor(HapiSpec spec) {
        return new BaseErroringAsserts<>(
                testProviders.stream().map(p -> p.apply(spec)).collect(Collectors.toList()));
    }
}
