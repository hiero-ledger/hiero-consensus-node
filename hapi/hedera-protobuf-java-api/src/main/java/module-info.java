/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

module com.hedera.protobuf.java.api {
    exports com.hedera.hapi.block.protoc;
    exports com.hedera.hapi.block.stream.input.protoc;
    exports com.hedera.hapi.block.stream.output.protoc;
    exports com.hedera.hapi.block.stream.protoc;
    exports com.hedera.hapi.node.state.tss.legacy;
    exports com.hedera.hapi.platform.event.legacy;
    exports com.hedera.hapi.platform.state.legacy;
    exports com.hedera.hapi.services.auxiliary.hints.legacy;
    exports com.hedera.hapi.services.auxiliary.history.legacy;
    exports com.hedera.hapi.services.auxiliary.tss.legacy;
    exports com.hedera.services.stream.proto;
    exports com.hederahashgraph.api.proto.java;
    exports com.hederahashgraph.service.proto.java;

    requires transitive com.google.common;
    requires transitive com.google.protobuf;
    requires transitive io.grpc.stub;
    requires transitive io.grpc;
    requires io.grpc.protobuf;
}
