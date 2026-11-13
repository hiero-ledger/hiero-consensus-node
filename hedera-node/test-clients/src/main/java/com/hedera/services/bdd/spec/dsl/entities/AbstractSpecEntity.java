// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.entities;

import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.handleExec;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.SpecEntityRegistrar;
import com.hedera.services.bdd.spec.dsl.operations.deferred.DoWithModelOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides implementation support for a {@link SpecEntity}.
 *
 * @param <O> the type of the operation used to create the entity
 * @param <M> the type of the entity's model
 */
public abstract class AbstractSpecEntity<O extends SpecOperation, M> implements SpecEntity {
    private static final Logger log = LogManager.getLogger(AbstractSpecEntity.class);

    /**
     * Represents the attempt to create an entity.
     *
     * @param op the operation used for the attempt
     * @param model the model object to be created
     * @param <S> the type of the operation
     * @param <R> the type of the model
     */
    protected record Creation<S extends SpecOperation, R>(S op, R model) {}

    /**
     * Represents the result of a successful entity creation.
     *
     * @param model the model object created
     * @param registrar the registrar for the entity
     * @param <R> the type of the model
     */
    protected record Result<R>(R model, SpecEntityRegistrar registrar) {}

    /**
     * Wraps a supplier of a future result, allowing us to defer scheduling that supplier until we know
     * it we are the privileged supplier out of potentially several concurrent threads.
     * @param <M> the type of the model returned by the supplier
     */
    private static class DeferredResult<M> {
        /**
         * The supplier of the future result.
         */
        private final Supplier<Result<M>> supplier;
        /**
         * The future result; created up front so losing threads can join it before the winner schedules the supplier.
         */
        private volatile CompletableFuture<Result<M>> future = new CompletableFuture<>();

        public DeferredResult(@NonNull final Supplier<Result<M>> supplier) {
            this.supplier = requireNonNull(supplier);
        }

        /**
         * Schedules the supplier to run asynchronously, marking it as the privileged supplier for this entity.
         */
        public void getAsync() {
            final var pending = future;
            try {
                pending.completeAsync(supplier);
            } catch (Throwable t) {
                pending.completeExceptionally(t);
                throw t;
            }
        }

        /**
         * Returns the future result.
         */
        public @NonNull CompletableFuture<Result<M>> future() {
            return future;
        }

        public void swap(@NonNull final Result<M> result) {
            requireNonNull(result);
            future = CompletableFuture.completedFuture(result);
        }
    }

    /**
     * Indicates whether the entity is locked.
     */
    private volatile boolean locked = false;
    /**
     * The name of the entity.
     */
    protected final String name;
    /**
     * A map of network names to atomic references to futures that will contain the results
     * of entity creations on the target networks.
     */
    private final Map<String, AtomicReference<DeferredResult<M>>> results = new ConcurrentHashMap<>();

    protected AbstractSpecEntity(@NonNull final String name) {
        this.name = requireNonNull(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void lock() {
        locked = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable SpecEntityRegistrar registrarFor(@NonNull final HederaNetwork network) {
        requireNonNull(network);
        return Optional.ofNullable(results.computeIfAbsent(network.name(), k -> new AtomicReference<>())
                        .get())
                .map(DeferredResult::future)
                .map(CompletableFuture::join)
                .map(Result::registrar)
                .orElse(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SpecEntityRegistrar createWith(@NonNull final HapiSpec spec) {
        final var network = spec.targetNetworkOrThrow();
        final var resultFutureRef = requireNonNull(results.get(network.name()));
        final var deferredResult = new DeferredResult<>(() -> {
            final var creation = newCreation(spec);
            // Throws if the creation op fails
            handleExec(spec, creation.op());
            final var result = resultForSuccessful(creation, spec);
            allRunFor(spec, postSuccessOps());
            return result;
        });
        if (resultFutureRef.compareAndSet(null, deferredResult)) {
            deferredResult.getAsync();
        }
        return resultFutureRef.get().future().join().registrar();
    }

    /**
     * Throws an exception if the entity is locked.
     */
    protected void throwIfLocked() {
        if (locked) {
            throw new IllegalStateException("Entity '" + name + "' is locked");
        }
    }

    /**
     * Retrieves the model corresponding to a network.
     *
     * @param network the network
     * @return the model
     */
    public M modelOrThrow(@NonNull final HederaNetwork network) {
        requireNonNull(network);
        return requireNonNull(requireNonNull(results.get(network.name())).get())
                .future()
                .join()
                .model();
    }

    /**
     * Executes a deferred operation on the model.
     *
     * @param function the function that computes the operation
     * @return the deferred operation
     */
    public DoWithModelOperation<M> doWith(@NonNull final Function<M, SpecOperation> function) {
        return new DoWithModelOperation<>(this, function);
    }

    /**
     * Supplies a {@link SpecOperation} that can be used to create the entity within the given spec.
     *
     * @param spec the spec
     * @return a new creation attempt
     */
    protected abstract Creation<O, M> newCreation(@NonNull HapiSpec spec);

    /**
     * Computes the registrar corresponding to a successful creation operation.
     *
     * @param creation the successful creation
     * @param spec the spec used to create the entity
     * @return the registrar
     */
    protected abstract Result<M> resultForSuccessful(@NonNull Creation<O, M> creation, @NonNull HapiSpec spec);

    /**
     * Supplies a list of operations to be executed after a successful creation.
     *
     * @return the list of operations
     */
    protected List<SpecOperation> postSuccessOps() {
        return emptyList();
    }

    /**
     * Replaces the result of a creation operation on a network.
     *
     * @param network the network
     * @param result the result
     */
    protected void replaceResult(@NonNull final HederaNetwork network, @NonNull final Result<M> result) {
        requireNonNull(network);
        requireNonNull(result);
        requireNonNull(results.get(network.name())).get().swap(result);
    }
}
