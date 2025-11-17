package com.hedera.services.bdd.suites.fees;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

@Tag(MATS)
@Tag(SIMPLE_FEES)
public class FileServiceSimpleFeesTest {
    private static final String MEMO = "Really quite something!";
    private static final String CIVILIAN = "civilian";
    private static final String KEY = "key";
    private static final double BASE_FEE_FILE_CREATE = 0.05;
    private static final double BASE_FEE_FILE_UPDATE = 0.05;
    private static final double BASE_FEE_FILE_DELETE = 0.007;
    private static final double BASE_FEE_FILE_APPEND = 0.05;
    private static final double BASE_FEE_FILE_GET_CONTENT = 0.0001;
    private static final double BASE_FEE_FILE_GET_FILE = 0.0001;

    @HapiTest
    @DisplayName("USD base fee as expected for file create transaction")
    final Stream<DynamicTest> fileCreateBaseUSDFee() {
        // 90 days considered for base fee
        var contents = "0".repeat(1000).getBytes();

        return hapiTest(
                newKeyNamed(KEY).shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key(KEY).balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("WACL", List.of(CIVILIAN)),
                fileCreate("test")
                        .memo(MEMO)
                        .key("WACL")
                        .contents(contents)
                        .payingWith(CIVILIAN)
                        .via("fileCreateBasic"),
                validateChargedUsd("fileCreateBasic", BASE_FEE_FILE_CREATE));
    }
}
