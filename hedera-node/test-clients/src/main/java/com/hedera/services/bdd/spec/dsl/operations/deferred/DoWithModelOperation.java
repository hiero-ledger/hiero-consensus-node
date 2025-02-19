/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.spec.dsl.operations.deferred;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.AbstractSpecEntity;
import com.hedera.services.bdd.spec.dsl.operations.AbstractSpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Function;

/**
 * Represents an operation that is deferred until a model is available.
 *
 * @param <M> the type of the model
 */
public class DoWithModelOperation<M> extends AbstractSpecOperation implements SpecOperation {

    private final AbstractSpecEntity<?, M> source;
    private final Function<M, SpecOperation> function;

    public DoWithModelOperation(
            @NonNull final AbstractSpecEntity<?, M> source, @NonNull final Function<M, SpecOperation> function) {
        super(List.of(source));
        this.source = source;
        this.function = requireNonNull(function);
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        return function.apply(source.modelOrThrow(spec.targetNetworkOrThrow()));
    }
}
