# HAPI Tests (Crypto) - Test Files

Tag: `@Tag(CRYPTO)`

## Test Files (33 files)

1. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/AutoAccountCreationSuite.java`
2. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/AutoAccountCreationUnlimitedAssociationsSuite.java`
3. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/AutoAccountUpdateSuite.java`
4. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoApproveAllowanceSuite.java`
5. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoCornerCasesSuite.java`
6. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoCreateSuite.java`
7. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoDeleteAllowanceSuite.java`
8. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoDeleteSuite.java`
9. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoGetInfoRegression.java`
10. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoGetRecordsRegression.java`
11. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoRecordsSanityCheckSuite.java`
12. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoTransferSuite.java`
13. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/CryptoUpdateSuite.java`
14. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/HollowAccountFinalizationSuite.java`
15. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/LeakyCryptoTestsSuite.java`
16. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/MiscCryptoSuite.java`
17. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/NftTransferSuite.java`
18. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/QueryPaymentSuite.java`
19. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/TransferWithCustomFixedFees.java`
20. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/TransferWithCustomFractionalFees.java`
21. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/TransferWithCustomRoyaltyFees.java`
22. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/TxnRecordRegression.java`
23. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/batch/AtomicAutoAccountCreationUnlimitedAssociationsSuite.java`
24. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/crypto/batch/AtomicAutoAccountUpdateSuite.java`
25. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip551/allowance/AtomicBatchApproveAllowanceTest.java`
26. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip551/AtomicAutoAccountCreationSuite.java`
27. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip904/TokenAirdropTest.java`
28. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip904/TokenAirdropWithOverriddenMaxAllowedPendingAirdropsTest.java`
29. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip904/TokenCancelAirdropDisabledTest.java`
30. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip904/TokenCancelAirdropTest.java`
31. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip904/TokenClaimAirdropTest.java`
32. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/queries/AsNodeOperatorQueriesTest.java`
33. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/staking/CreateStakersTest.java`

## Potential Issues

### Duplicated Tests

The following Atomic* tests are documented as "direct copies" of their parent tests:
- `AtomicAutoAccountCreationUnlimitedAssociationsSuite.java` → copy of `AutoAccountCreationUnlimitedAssociationsSuite.java`
- `AtomicAutoAccountUpdateSuite.java` → copy of `AutoAccountUpdateSuite.java`
- `AtomicBatchApproveAllowanceTest.java` → copy of crypto approve allowance tests
- `AtomicAutoAccountCreationSuite.java` → copy of `AutoAccountCreationSuite.java`
