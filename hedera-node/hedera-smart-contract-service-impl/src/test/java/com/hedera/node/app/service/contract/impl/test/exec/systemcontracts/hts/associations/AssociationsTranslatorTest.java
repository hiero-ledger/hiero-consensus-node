// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.associations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations.AssociationsTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssociationsTranslatorTest extends CallTestBase {

    @Test
    void dispatchesAssociateType() {
        given(gasCalculator.gasRequirement(TransactionBody.DEFAULT, DispatchType.ASSOCIATE, AccountID.DEFAULT))
                .willReturn(123L);
        assertEquals(
                123L,
                AssociationsTranslator.gasRequirement(
                        TransactionBody.DEFAULT, gasCalculator, mockEnhancement(), AccountID.DEFAULT));
    }
}
