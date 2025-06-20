// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.SequencedCollection;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A registry for all the system contract methods - their names, selectors, and signatures.
 * <p>
 * Methods are added to this registry when they're defined in the various `FooTranslator` classes,
 * which, since they're all (or should be) Dagger singletons, is done as the smart contract service
 * is coming up.  Thus, the completed registry is available once processing starts, and so is ready
 * for use to enumerate all system contract methods.
 * <p>
 * The principal use case for this registry is to be able to generate various per-system-contract-method
 * metrics.
 */
@Singleton
public class SystemContractMethodRegistry {

    private static final int EXPECTED_SYSTEM_CONTRACT_METHODS_UPPER_BOUND = 250;
    private final ConcurrentHashMap<String, SystemContractMethod> byName =
            new ConcurrentHashMap<>(EXPECTED_SYSTEM_CONTRACT_METHODS_UPPER_BOUND);

    // The static constant `SystemContractMethod`s in each of the translator classes do _not_ have
    // the `SystemContract` field set.  That's set later after all the static fields are created.
    // So there needs to be a way to get from the version _without_ the system contract to the version
    // _with_ the system contract.  That's this map.
    //
    // Thus, in the running system there are two instances of `SystemContractMethod` for each system
    // contract method:  One without the `SystemContract`, one with.  This is an acceptable fixed
    // overhead.  Alternative would have been to bite the bullet and explicitly set the system contract
    // field in the constructor of each of the static constants in each of the translator classes.
    // Perhaps that's a refactor that would be worth it sometime.
    private final ConcurrentHashMap<SystemContractMethod, SystemContractMethod> withoutToWithSystemContract =
            new ConcurrentHashMap<>(EXPECTED_SYSTEM_CONTRACT_METHODS_UPPER_BOUND);

    @Inject
    public SystemContractMethodRegistry() {
        requireNonNull(SystemContractMethod.SystemContract.HTS); // DEBUGGING
    }

    /**
     * Add a system contract method into the registry of system contract methods
     */
    public void register(
            @NonNull final SystemContractMethod systemContractMethodWithoutContract,
            @NonNull final SystemContractMethod systemContractMethodWithContract) {
        requireNonNull(systemContractMethodWithoutContract);
        requireNonNull(systemContractMethodWithContract);

        var keyName = systemContractMethodWithContract.variatedMethodName();
        if (systemContractMethodWithContract.via() == CallVia.PROXY)
            keyName += systemContractMethodWithContract.via().asSuffix();

        byName.putIfAbsent(keyName, systemContractMethodWithContract);
        withoutToWithSystemContract.putIfAbsent(systemContractMethodWithoutContract, systemContractMethodWithContract);
    }

    // Queries:

    public long size() {
        return byName.size();
    }

    public @NonNull SystemContractMethod fromMissingContractGetWithContract(
            @NonNull final SystemContractMethod systemContractMethodWithoutContract) {
        if (systemContractMethodWithoutContract.systemContract().isPresent())
            return systemContractMethodWithoutContract;
        else return withoutToWithSystemContract.get(systemContractMethodWithoutContract);
    }

    public @NonNull SequencedCollection<String> allQualifiedMethods() {
        return allMethodsGivenMapper(SystemContractMethod::qualifiedMethodName);
    }

    public @NonNull SequencedCollection<String> allSignatures() {
        return allMethodsGivenMapper(SystemContractMethod::signature);
    }

    public @NonNull SequencedCollection<String> allSignaturesWithReturns() {
        return allMethodsGivenMapper(SystemContractMethod::signatureWithReturn);
    }

    public @NonNull SequencedCollection<String> allMethodsGivenMapper(
            @NonNull final java.util.function.Function<SystemContractMethod, String> methodMapper) {
        return byName.values().stream().map(methodMapper).sorted().toList();
    }

    public @NonNull Collection<SystemContractMethod> allMethods() {
        return byName.values();
    }

    @VisibleForTesting
    public @NonNull String allMethodsAsTable() {
        final var allMethods = allMethods().stream()
                .sorted(comparing(SystemContractMethod::qualifiedMethodName))
                .toList();
        final var sb = new StringBuilder();
        for (final var method : allMethods) {
            var categoriesSuffix = method.categoriesSuffix();
            if (categoriesSuffix.isEmpty()) categoriesSuffix = "No Category";
            else categoriesSuffix = categoriesSuffix.substring(1);
            sb.append("%s: 0x%s - %s - %s\n"
                    .formatted(
                            method.qualifiedMethodName(),
                            method.selectorHex(),
                            method.signatureWithReturn(),
                            categoriesSuffix));
        }
        return sb.toString();
    }
}
