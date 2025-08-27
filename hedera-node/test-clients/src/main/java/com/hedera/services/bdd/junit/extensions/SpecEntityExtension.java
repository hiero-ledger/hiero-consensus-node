// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static java.lang.reflect.Modifier.isStatic;
import static org.junit.platform.commons.support.AnnotationSupport.findAnnotatedFields;

import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.Key;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecKey;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecToken;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class SpecEntityExtension implements ParameterResolver, BeforeAllCallback {
    @Override
    public boolean supportsParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext) {
        return SpecEntity.class.isAssignableFrom(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        final var parameter = parameterContext.getParameter();
        final var entityType = parameterContext.getParameter().getType();
        if (entityType == SpecAccount.class) {
            return accountFrom(
                    parameter.isAnnotationPresent(Account.class) ? parameter.getAnnotation(Account.class) : null,
                    Time.getCurrent().now() + parameter.getName());
        } else if (entityType == SpecContract.class) {
            if (!parameter.isAnnotationPresent(Contract.class)) {
                throw new IllegalArgumentException("Missing @ContractSpec annotation");
            }
            return SpecContract.contractFrom(parameter.getAnnotation(Contract.class));
        } else if (entityType == SpecFungibleToken.class) {
            if (!parameter.isAnnotationPresent(FungibleToken.class)) {
                throw new IllegalArgumentException("Missing @FungibleTokenSpec annotation");
            }
            return SpecFungibleToken.from(
                    parameter.getAnnotation(FungibleToken.class),
                    Time.getCurrent().now() + parameter.getName());
        } else if (entityType == SpecNonFungibleToken.class) {
            if (!parameter.isAnnotationPresent(NonFungibleToken.class)) {
                throw new IllegalArgumentException("Missing @NonFungibleTokenSpec annotation");
            }
            return nonFungibleTokenFrom(
                    parameter.getAnnotation(NonFungibleToken.class),
                    Time.getCurrent().now() + parameter.getName());
        } else if (entityType == SpecKey.class) {
            return keyFrom(
                    parameter.isAnnotationPresent(Key.class) ? parameter.getAnnotation(Key.class) : null,
                    parameter.getName());
        } else {
            throw new ParameterResolutionException("Unsupported entity type " + entityType);
        }
    }

    @Override
    public void beforeAll(@NonNull final ExtensionContext context) throws Exception {
        // Inject spec contracts into static fields annotated with @ContractSpec
        for (final var field : findAnnotatedFields(
                context.getRequiredTestClass(), Contract.class, staticFieldSelector(SpecContract.class))) {
            final var contract = SpecContract.contractFrom(field.getAnnotation(Contract.class));
            injectValueIntoField(field, contract);
        }

        // Inject spec fungible tokens into static fields annotated with @FungibleTokenSpec
        for (final var field : findAnnotatedFields(
                context.getRequiredTestClass(), FungibleToken.class, staticFieldSelector(SpecFungibleToken.class))) {
            final var token = SpecFungibleToken.from(field.getAnnotation(FungibleToken.class), field.getName());
            injectValueIntoField(field, token);
        }

        // Inject spec non-fungible tokens into static fields annotated with @NonFungibleTokenSpec
        for (final var field : findAnnotatedFields(
                context.getRequiredTestClass(),
                NonFungibleToken.class,
                staticFieldSelector(SpecNonFungibleToken.class))) {
            final var token = nonFungibleTokenFrom(field.getAnnotation(NonFungibleToken.class), field.getName());
            injectValueIntoField(field, token);
        }

        // Inject spec accounts into static fields annotated with @AccountSpec
        for (final var field : findAnnotatedFields(
                context.getRequiredTestClass(), Account.class, staticFieldSelector(SpecAccount.class))) {
            final var account = accountFrom(field.getAnnotation(Account.class), field.getName());
            injectValueIntoField(field, account);
        }

        // Inject spec keys into static fields annotated with @KeySpec
        for (final var field :
                findAnnotatedFields(context.getRequiredTestClass(), Key.class, staticFieldSelector(SpecKey.class))) {
            final var key = keyFrom(field.getAnnotation(Key.class), field.getName());
            injectValueIntoField(field, key);
        }
    }

    private SpecNonFungibleToken nonFungibleTokenFrom(
            @NonNull final NonFungibleToken annotation, @NonNull final String defaultName) {
        final var token = new SpecNonFungibleToken(annotation.name().isBlank() ? defaultName : annotation.name());
        SpecToken.customizeToken(token, annotation.keys(), annotation.useAutoRenewAccount());
        token.setNumPreMints(annotation.numPreMints());
        return token;
    }

    private SpecAccount accountFrom(@Nullable final Account annotation, @NonNull final String defaultName) {
        final var name = Optional.ofNullable(annotation)
                .map(Account::name)
                .filter(n -> !n.isBlank())
                .orElse(defaultName);
        final var account = new SpecAccount(name);
        if (annotation != null) {
            final var builder = account.builder();
            if (annotation.centBalance() > 0L) {
                account.centBalance(annotation.centBalance());
            } else if (annotation.tinybarBalance() > 0L) {
                builder.tinybarBalance(annotation.tinybarBalance());
            }
            if (annotation.stakedNodeId() > -1L) {
                builder.stakedNodeId(annotation.stakedNodeId());
            }
            builder.maxAutoAssociations(annotation.maxAutoAssociations());
        }
        return account;
    }

    private SpecKey keyFrom(@Nullable final Key annotation, @NonNull final String defaultName) {
        final var name = Optional.ofNullable(annotation)
                .map(Key::name)
                .filter(n -> !n.isBlank())
                .orElse(defaultName);
        final var type = Optional.ofNullable(annotation).map(Key::type).orElse(SpecKey.Type.ED25519);
        return new SpecKey(name, type);
    }

    private void injectValueIntoField(@NonNull final Field field, @NonNull final Object value)
            throws IllegalAccessException {
        final var accessible = field.isAccessible();
        field.setAccessible(true);
        field.set(null, value);
        field.setAccessible(accessible);
    }

    private static Predicate<Field> staticFieldSelector(@NonNull final Class<?> type) {
        return f -> isStatic(f.getModifiers()) && f.getType() == type;
    }
}
