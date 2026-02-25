// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

public class TestService implements Service {
    private final String name;
    private final List<Schema> schemas;

    public TestService(@NonNull final String name) {
        this.name = requireNonNull(name);
        this.schemas = List.of();
    }

    public TestService(@NonNull final String name, @NonNull final List<Schema> schemas) {
        this.name = requireNonNull(name);
        this.schemas = requireNonNull(schemas);
        // Just something to keep checkModuleInfo from claiming we don't
        // require com.hedera.node.hapi.utils
        requireNonNull(EthTxData.class);
    }

    @NonNull
    @Override
    public String getServiceName() {
        return name;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        schemas.forEach(registry::register);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private final List<Schema> schemas = new ArrayList<>();

        private Builder() {}

        public Builder name(@NonNull final String name) {
            this.name = requireNonNull(name);
            return this;
        }

        public Builder schema(@NonNull final TestSchema.Builder schemaBuilder) {
            this.schemas.add(schemaBuilder.build());
            return this;
        }

        public Builder schema(@NonNull final Schema schema) {
            this.schemas.add(schema);
            return this;
        }

        public TestService build() {
            return new TestService(name, schemas);
        }
    }
}
