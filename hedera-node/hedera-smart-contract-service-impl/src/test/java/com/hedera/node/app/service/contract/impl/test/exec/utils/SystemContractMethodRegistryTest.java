// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.utils;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_CONTRACT_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.getevmaddressalias.EvmAddressAliasTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias.IsValidAliasTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemContractMethodRegistryTest {

    @Mock
    ContractMetrics contractMetrics;

    @Test
    void testMethodCreationHappyPath() {

        final var signature = new Function("collectReward(string)", ReturnTypes.ADDRESS);
        final var subject = SystemContractMethod.declare(
                        signature.getCanonicalSignature(),
                        signature.getOutputs().toString())
                .withContract(SystemContract.EXCHANGE)
                .withVia(CallVia.DIRECT);

        assertThat(subject.methodName()).isEqualTo("collectReward");
        assertThat(subject.qualifiedMethodName()).isEqualTo("EXCHANGE.collectReward");
        assertThat(subject.signature()).isEqualTo("collectReward(string)");
        assertThat(subject.signatureWithReturn()).isEqualTo("collectReward(string):(address)");
        assertThat(subject.selectorLong()).isEqualTo(0x3cbf0049L);
        assertThat(subject.selectorHex()).isEqualTo("3cbf0049");
        assertThat(subject.supportedAddresses()).containsOnly(ContractID.DEFAULT);
    }

    @Test
    void testMethodCreationVariations() {

        final var signature = new Function("collectReward(string)", ReturnTypes.ADDRESS);

        // Proxy
        final var subjectProxy = SystemContractMethod.declare(
                        signature.getCanonicalSignature(),
                        signature.getOutputs().toString())
                .withContract(SystemContract.EXCHANGE)
                .withVia(CallVia.PROXY)
                .withSupportedAddresses(HTS_167_CONTRACT_ID, HAS_CONTRACT_ID);
        assertThat(subjectProxy.qualifiedMethodName()).isEqualTo("EXCHANGE(PROXY).collectReward");

        // Variant method name
        final var subjectOverrideName = SystemContractMethod.declare(
                        signature.getCanonicalSignature(),
                        signature.getOutputs().toString())
                .withContract(SystemContract.EXCHANGE)
                .withVariant(Variant.NFT);
        assertThat(subjectOverrideName.qualifiedMethodName()).isEqualTo("EXCHANGE.collectReward_NFT");
        assertThat(subjectOverrideName.signature()).isEqualTo("collectReward(string)");
        assertThat(subjectOverrideName.selectorLong()).isEqualTo(0x3cbf0049L);

        // Proxy
        final var subjectSupportedAddresses = SystemContractMethod.declare(
                        signature.getCanonicalSignature(),
                        signature.getOutputs().toString())
                .withContract(SystemContract.EXCHANGE)
                .withSupportedAddresses(HTS_167_CONTRACT_ID, HAS_CONTRACT_ID);
        assertThat(subjectSupportedAddresses.supportedAddresses()).containsOnly(HTS_167_CONTRACT_ID, HAS_CONTRACT_ID);
    }

    @Test
    void testHappyMethodRegistrations() {

        final var subject = new SystemContractMethodRegistry();

        // Add some registrations by simply creating instances of classes known to register methods

        final var t1 = new IsValidAliasTranslator(subject, contractMetrics);
        final var t2 = new HbarApproveTranslator(subject, contractMetrics);
        final var t3 = new EvmAddressAliasTranslator(subject, contractMetrics);

        // Test expected methods are registered (test data is known from looking at the classes involved)

        //        HAS(PROXY).hbarApprove: 0x86aff07c - hbarApprove(address,int256):(int64)
        //        HAS.getEvmAddressAlias: 0xdea3d081 - getEvmAddressAlias(address):(int64,address)
        //        HAS.hbarApprove:        0xa0918464 - hbarApprove(address,address,int256):(int64)
        //        HAS.isValidAlias:       0x308ef301 - isValidAlias(address):(bool)

        final var actualAllQualifiedMethods = subject.allQualifiedMethods();
        assertThat(actualAllQualifiedMethods)
                .hasSize(4)
                .containsExactlyInAnyOrder(
                        "HAS.isValidAlias", "HAS.getEvmAddressAlias", "HAS.hbarApprove", "HAS(PROXY).hbarApprove");

        final var actualAllSignatures = subject.allSignatures();
        assertThat(actualAllSignatures)
                .hasSize(4)
                .containsExactlyInAnyOrder(
                        "getEvmAddressAlias(address)",
                        "hbarApprove(address,address,int256)",
                        "hbarApprove(address,int256)",
                        "isValidAlias(address)");

        final var actualAllSignaturesWithReturns = subject.allSignaturesWithReturns();
        assertThat(actualAllSignaturesWithReturns)
                .hasSize(4)
                .containsExactlyInAnyOrder(
                        "getEvmAddressAlias(address):(int64,address)",
                        "hbarApprove(address,address,int256):(int64)",
                        "hbarApprove(address,int256):(int64)",
                        "isValidAlias(address):(bool)");
    }

    @Test
    void testDuplicateMethodRegistrationRegistersOnlyOneCopy() {

        final var subject = new SystemContractMethodRegistry();

        // Add some registrations twice - this might happen if a `FooTranslator` isn't marked `@Singleton`
        final var t1 = new IsValidAliasTranslator(subject, contractMetrics);
        final var t2 = new IsValidAliasTranslator(subject, contractMetrics);

        // Test only one method is registered

        final var actualAllQualifiedMethods = subject.allQualifiedMethods();
        assertThat(actualAllQualifiedMethods).hasSize(1).containsExactly("HAS.isValidAlias");

        final var actualAllSignatures = subject.allSignatures();
        assertThat(actualAllSignatures).hasSize(1).containsExactly("isValidAlias(address)");

        final var actualAllSignaturesWithReturns = subject.allSignaturesWithReturns();
        assertThat(actualAllSignaturesWithReturns).hasSize(1).containsExactly("isValidAlias(address):(bool)");
    }
}
