// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.nodes;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.ExceptionMsgUtils;
import com.hedera.services.yahcli.test.YahcliTestBase;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

public class NodesCommandTest extends YahcliTestBase {

    @Nested
    class UpdateCommandParams {
        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3");
            assertCommandHierarchyOf(result, "yahcli", "nodes", "update");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3");
            final var subCmd = findSubcommand(result, "update").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes update help");
            assertCommandHierarchyOf(result, "yahcli", "nodes", "update", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " nodes update help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli nodes update", "update an existing node", "Commands:");
        }

        @Test
        void nodeIdOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3");
            final var optValue = findOption(result, "update", "--nodeId").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("3");
        }

        @Test
        void accountNumOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 --accountNum 5");
            final var optValue = findOption(result, "update", "--accountNum").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("5");
        }

        @Test
        void descriptionOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 --description \"Test Node\"");
            final var optValue = findOption(result, "update", "--description").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("Test Node");
        }

        @Test
        void gossipEndpointsOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 -g 10.0.0.1:50070,my.fqdn.com:50070");
            final var optValue =
                    findOption(result, "update", "--gossipEndpoints").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("10.0.0.1:50070,my.fqdn.com:50070");
        }

        @Test
        void serviceEndpointsOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 -s 10.0.0.1:50211,my.fqdn.com:50211");
            final var optValue =
                    findOption(result, "update", "--serviceEndpoints").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("10.0.0.1:50211,my.fqdn.com:50211");
        }

        @Test
        void gossipCaCertificateOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 --gossipCaCertificate cert/path.pem");
            final var optValue =
                    findOption(result, "update", "--gossipCaCertificate").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("cert/path.pem");
        }

        @Test
        void hapiCertificateOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 --hapiCertificate cert/path.pem");
            final var optValue =
                    findOption(result, "update", "--hapiCertificate").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("cert/path.pem");
        }

        @Test
        void adminKeyOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 --adminKey keys/admin.pem");
            final var optValue = findOption(result, "update", "--adminKey").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("keys/admin.pem");
        }

        @Test
        void newAdminKeyOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 --newAdminKey keys/newadmin.pem");
            final var optValue = findOption(result, "update", "--newAdminKey").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("keys/newadmin.pem");
        }

        @Test
        void stopDecliningRewardsOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 --stopDecliningRewards");
            final var optValue =
                    findOption(result, "update", "--stop-declining-rewards").orElseThrow();
            assertThat((Boolean) optValue.getValue()).isEqualTo(true);
        }

        @Test
        void startDecliningRewardsOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 --startDecliningRewards");
            final var optValue =
                    findOption(result, "update", "--start-declining-rewards").orElseThrow();
            assertThat((Boolean) optValue.getValue()).isEqualTo(true);
        }

        @Test
        void grpcProxyEndpointOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3 --grpcProxyEndpoint 10.0.0.1:50051");
            final var optValue =
                    findOption(result, "update", "--grpcProxyEndpoint").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("10.0.0.1:50051");
        }

        @Test
        void parsesShortOptionsCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes update -n 3 -a 5 -d Test -g endpoint1 -s endpoint2");

            var nodeIdValue = findOption(result, "update", "--nodeId").orElseThrow();
            assertThat((String) nodeIdValue.getValue()).isEqualTo("3");

            var accountNumValue = findOption(result, "update", "--accountNum").orElseThrow();
            assertThat((String) accountNumValue.getValue()).isEqualTo("5");

            var descriptionValue = findOption(result, "update", "--description").orElseThrow();
            assertThat((String) descriptionValue.getValue()).isEqualTo("Test");

            var gossipEndpointsValue =
                    findOption(result, "update", "--gossipEndpoints").orElseThrow();
            assertThat((String) gossipEndpointsValue.getValue()).isEqualTo("endpoint1");

            var serviceEndpointsValue =
                    findOption(result, "update", "--serviceEndpoints").orElseThrow();
            assertThat((String) serviceEndpointsValue.getValue()).isEqualTo("endpoint2");
        }

        @Test
        void parsesPfxOptionsCorrectly() {
            final var result = parseArgs(typicalGlobalOptions()
                    + " nodes update --nodeId 3 --gossipCaCertificatePfx cert.pfx --gossipCaCertificateAlias myalias");

            var pfxValue =
                    findOption(result, "update", "--gossipCaCertificatePfx").orElseThrow();
            assertThat((String) pfxValue.getValue()).isEqualTo("cert.pfx");

            var aliasValue =
                    findOption(result, "update", "--gossipCaCertificateAlias").orElseThrow();
            assertThat((String) aliasValue.getValue()).isEqualTo("myalias");
        }

        @Test
        void allOptionsAreOptionalExceptNodeId() {
            // NodeId is required for the command to be valid functionally,
            // but we'll verify all other options are optional
            final var result = parseArgs(typicalGlobalOptions() + " nodes update --nodeId 3");

            // All of these should be present but with null values
            var accountNumOpt = findOption(result, "update", "--accountNum");
            assertThat(accountNumOpt).isPresent();
            assertThat((String) accountNumOpt.get().getValue()).isNull();

            var descriptionOpt = findOption(result, "update", "--description");
            assertThat(descriptionOpt).isPresent();
            assertThat((String) descriptionOpt.get().getValue()).isNull();

            var gossipEndpointsOpt = findOption(result, "update", "--gossipEndpoints");
            assertThat(gossipEndpointsOpt).isPresent();
            assertThat((String) gossipEndpointsOpt.get().getValue()).isNull();
        }
    }

    @Nested
    class CreateCommandParams {
        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5");
            assertCommandHierarchyOf(result, "yahcli", "nodes", "create");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5");
            final var subCmd = findSubcommand(result, "create").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes create help");
            assertCommandHierarchyOf(result, "yahcli", "nodes", "create", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " nodes create help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli nodes create", "Creates a new node", "Commands:");
        }

        @Test
        void accountNumOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5");
            final var optValue = findOption(result, "create", "--accountNum").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("5");
        }

        @Test
        void descriptionOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5 --description 'Test Node'");
            final var optValue = findOption(result, "create", "--description").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("Test Node");
        }

        @Test
        void gossipEndpointsOptionParsesCorrectly() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " nodes create --accountNum 5 -g 10.0.0.1:50070,my.fqdn.com:50070");
            final var optValue =
                    findOption(result, "create", "--gossipEndpoints").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("10.0.0.1:50070,my.fqdn.com:50070");
        }

        @Test
        void serviceEndpointsOptionParsesCorrectly() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " nodes create --accountNum 5 -s 10.0.0.1:50211,my.fqdn.com:50211");
            final var optValue =
                    findOption(result, "create", "--serviceEndpoints").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("10.0.0.1:50211,my.fqdn.com:50211");
        }

        @Test
        void gossipCaCertificateOptionParsesCorrectly() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " nodes create --accountNum 5 --gossipCaCertificate cert/path.pem");
            final var optValue =
                    findOption(result, "create", "--gossipCaCertificate").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("cert/path.pem");
        }

        @Test
        void hapiCertificateOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5 --hapiCertificate cert/path.pem");
            final var optValue =
                    findOption(result, "create", "--hapiCertificate").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("cert/path.pem");
        }

        @Test
        void adminKeyOptionParsesCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5 --adminKey keys/admin.pem");
            final var optValue = findOption(result, "create", "--adminKey").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("keys/admin.pem");
        }

        @Test
        void declineRewardsOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5 --declineRewards true");
            final var optValue =
                    findOption(result, "create", "--declineRewards").orElseThrow();
            assertThat((Boolean) optValue.getValue()).isEqualTo(true);
        }

        @Test
        void declineRewardsDefaultIsTrue() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5");
            final var optValue =
                    findOption(result, "create", "--declineRewards").orElseThrow();
            assertThat((Boolean) optValue.getValue()).isEqualTo(true);
        }

        @Test
        void declineRewardsFlagWithoutArgumentIsTrue() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5 --declineRewards");
            final var optValue =
                    findOption(result, "create", "--declineRewards").orElseThrow();
            assertThat((Boolean) optValue.getValue()).isEqualTo(true);
        }

        @Test
        void grpcProxyEndpointOptionParsesCorrectly() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " nodes create --accountNum 5 --grpcProxyEndpoint 10.0.0.1:50051");
            final var optValue =
                    findOption(result, "create", "--grpcProxyEndpoint").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("10.0.0.1:50051");
        }

        @Test
        void parsesShortOptionsCorrectly() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " nodes create -a 5 -d \"Test\" -g endpoint1 -s endpoint2");

            var accountNumValue = findOption(result, "create", "--accountNum").orElseThrow();
            assertThat((String) accountNumValue.getValue()).isEqualTo("5");

            var descriptionValue = findOption(result, "create", "--description").orElseThrow();
            assertThat((String) descriptionValue.getValue()).isEqualTo("Test");

            var gossipEndpointsValue =
                    findOption(result, "create", "--gossipEndpoints").orElseThrow();
            assertThat((String) gossipEndpointsValue.getValue()).isEqualTo("endpoint1");

            var serviceEndpointsValue =
                    findOption(result, "create", "--serviceEndpoints").orElseThrow();
            assertThat((String) serviceEndpointsValue.getValue()).isEqualTo("endpoint2");
        }

        @Test
        void parsesPfxOptionsCorrectly() {
            final var result = parseArgs(
                    typicalGlobalOptions()
                            + " nodes create --accountNum 5 --gossipCaCertificatePfx cert.pfx --gossipCaCertificateAlias myalias");

            var pfxValue =
                    findOption(result, "create", "--gossipCaCertificatePfx").orElseThrow();
            assertThat((String) pfxValue.getValue()).isEqualTo("cert.pfx");

            var aliasValue =
                    findOption(result, "create", "--gossipCaCertificateAlias").orElseThrow();
            assertThat((String) aliasValue.getValue()).isEqualTo("myalias");
        }
    }

    @Nested
    class DeleteCommandParams {
        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes delete --nodeId 3");
            assertCommandHierarchyOf(result, "yahcli", "nodes", "delete");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes delete --nodeId 3");
            final var subCmd = findSubcommand(result, "delete").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes delete help");
            assertCommandHierarchyOf(result, "yahcli", "nodes", "delete", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " nodes delete help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli nodes delete", "Delete a node", "Commands:");
        }

        @Test
        void nodeIdOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes delete --nodeId 3");
            final var optValue = findOption(result, "delete", "--nodeId").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("3");
        }

        @Test
        void shortNodeIdOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes delete -n 3");
            final var optValue = findOption(result, "delete", "--nodeId").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("3");
        }

        @Test
        void adminKeyOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes delete --nodeId 3 --adminKey keys/admin.pem");
            final var optValue = findOption(result, "delete", "--adminKey").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("keys/admin.pem");
        }

        @Test
        void shortAdminKeyOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes delete -n 3 -k keys/admin.pem");
            final var optValue = findOption(result, "delete", "--adminKey").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("keys/admin.pem");
        }

        @Test
        void adminKeyOptionIsOptional() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes delete --nodeId 3");
            final var optValue = findOption(result, "delete", "--adminKey");
            assertThat(optValue.isPresent()).isTrue();
            assertThat((String) optValue.get().getValue()).isNull();
        }

        @Test
        void multipleOptionsParseCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " nodes delete -n 3 -k keys/admin.pem");

            var nodeIdValue = findOption(result, "delete", "--nodeId").orElseThrow();
            assertThat((String) nodeIdValue.getValue()).isEqualTo("3");

            var adminKeyValue = findOption(result, "delete", "--adminKey").orElseThrow();
            assertThat((String) adminKeyValue.getValue()).isEqualTo("keys/admin.pem");
        }
    }

    // TODO: check if this is needed
    @Nested
    class NodesCommandParamTests {
        private static final String[] NODE_IDS = new String[] {"0", "1", "3", "10", "999"};

        private static final String[] ENDPOINT_COMBINATIONS = new String[] {
            "10.0.0.1:50070",
            "my.fqdn.com:50070",
            "10.0.0.1:50070,my.fqdn.com:50070",
            "node1.example.com:50211,node2.example.com:50211,node3.example.com:50211"
        };

        @SuppressWarnings("unused")
        static Stream<Arguments> nodeIds() {
            return Stream.of(NODE_IDS).map(Arguments::of);
        }

        @SuppressWarnings("unused")
        static Stream<Arguments> endpointCombinations() {
            return Stream.of(ENDPOINT_COMBINATIONS).map(Arguments::of);
        }

        @ParameterizedTest
        @MethodSource("nodeIds")
        void parsesNodeIdsInUpdateCommand(String nodeId) {
            final var result = parseArgs(typicalGlobalOptions() + " nodes update --nodeId " + nodeId);
            final var optValue = findOption(result, "update", "--nodeId").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo(nodeId);
        }

        @ParameterizedTest
        @MethodSource("endpointCombinations")
        void parsesGossipEndpointsInCreateCommand(String endpoints) {
            final var result = parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5 -g " + endpoints);
            final var optValue =
                    findOption(result, "create", "--gossipEndpoints").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo(endpoints);
        }

        @ParameterizedTest
        @MethodSource("endpointCombinations")
        void parsesServiceEndpointsInCreateCommand(String endpoints) {
            final var result = parseArgs(typicalGlobalOptions() + " nodes create --accountNum 5 -s " + endpoints);
            final var optValue =
                    findOption(result, "create", "--serviceEndpoints").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo(endpoints);
        }
    }

    @Nested
    class NodesCommandNegativeTests {
        @Test
        @Disabled("(FUTURE) Maybe nodeId should be marked as required")
        void nodeIdIsRequiredParameterForUpdate() {
            final var exception = assertThrows(
                    CommandLine.MissingParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " nodes update"));
            ExceptionMsgUtils.assertMissingRequiredParamMsg(exception, "nodeId");
        }

        @Test
        void nodeIdValueIsRequiredWhenSpecified() {
            final var exception = assertThrows(
                    CommandLine.ParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " nodes update --nodeId"));
            assertThat(exception.getMessage()).contains("Missing required parameter for option '--nodeId'");
        }

        @Test
        @Disabled("(FUTURE) Maybe nodeId should be marked as required")
        void accountNumIsRequiredParameterForCreate() {
            final var exception = assertThrows(
                    CommandLine.MissingParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " nodes create"));
            ExceptionMsgUtils.assertMissingRequiredParamMsg(exception, "accountNum");
        }

        @Test
        void accountNumValueIsRequiredWhenSpecified() {
            final var exception = assertThrows(
                    CommandLine.ParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " nodes create --accountNum"));
            assertThat(exception.getMessage()).contains("Missing required parameter for option '--accountNum'");
        }

        @Test
        @Disabled("(FUTURE) Maybe nodeId should be marked as required")
        void nodeIdIsRequiredParameterForDelete() {
            final var exception = assertThrows(
                    CommandLine.MissingParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " nodes delete"));
            ExceptionMsgUtils.assertMissingRequiredParamMsg(exception, "nodeId");
        }

        @Test
        void adminKeyValueIsRequiredWhenSpecified() {
            final var exception = assertThrows(
                    CommandLine.ParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " nodes delete --nodeId 3 --adminKey"));
            assertThat(exception.getMessage()).contains("Missing required parameter for option '--adminKey'");
        }
    }
}
