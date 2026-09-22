// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.schemas;

import static com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema.ACCOUNT_NODE_REL_STATE_ID;
import static com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema.ACCOUNT_NODE_REL_STATE_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema;
import org.junit.jupiter.api.Test;

class V068AddressBookSchemaTest {
    @Test
    void registersAccountNodeRelationMap() {
        final var schema = new V068AddressBookSchema();

        assertThat(schema.getVersion())
                .isEqualTo(
                        SemanticVersion.newBuilder().major(0).minor(68).patch(0).build());
        assertThat(schema.statesToCreate()).singleElement().satisfies(def -> {
            assertThat(def.stateKey()).isEqualTo(ACCOUNT_NODE_REL_STATE_KEY);
            assertThat(def.stateId()).isEqualTo(ACCOUNT_NODE_REL_STATE_ID);
            assertThat(def.singleton()).isFalse();
            assertThat(def.keyCodec()).isEqualTo(AccountID.PROTOBUF);
            assertThat(def.valueCodec()).isEqualTo(NodeId.PROTOBUF);
        });
    }
}
