// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.ArrayList;
import java.util.List;

/** This validator validates expiry contract records at the end of test run */
public class ExpiryRecordsValidator implements RecordStreamValidator {
    public ExpiryRecordsValidator() {}

    private static final String AUTO_RENEWAL_MEMO = " was automatically renewed";
    private static final String AUTO_EXPIRY_MEMO = " was automatically deleted";

    @Override
    @SuppressWarnings("java:S106")
    public void validateRecordsAndSidecars(final List<RecordWithSidecars> recordsWithSidecars) {
        final var renewalRecords = new ArrayList<TransactionRecord>();
        final var expiredRecords = new ArrayList<TransactionRecord>();
        // gets expiry and renewal records from record stream based on the memo
        getExpiryRecordsFrom(recordsWithSidecars, renewalRecords, expiredRecords);
        System.out.println("Expired records: " + expiredRecords);
        System.out.println("Renewal records: " + renewalRecords);

        validateRenewalRecords(renewalRecords);
        validateExpiredRecords(expiredRecords);
    }

    /**
     * Validate the transferred amount from expired entity is always negative and the received
     * amount is always positive in transfer list and token transfer list. Also validates the serial
     * numbers transferred from sender are always positive and sender of nfts is the same as the
     * expired entity.
     *
     * @param expiredRecords list of expired records from stream
     */
    private void validateExpiredRecords(List<TransactionRecord> expiredRecords) {
        for (final var item : expiredRecords) {
            assertTrue(item.getReceipt().getStatus().equals(SUCCESS));

            final var expiredNum = getEntityNumFromAutoExpiryMemo(item.getMemo());

            for (final var transfers : item.getTransferList().getAccountAmountsList()) {
                if (transfers.getAccountID().getAccountNum() == expiredNum) {
                    assertTrue(
                            transfers.getAmount() < 0,
                            "Transfer amount in transfer list " + "from expired entity should be negative");
                } else {
                    assertTrue(
                            transfers.getAmount() > 0,
                            "Transfer amount in transfer list to other" + " entities should be positive");
                }
            }

            for (final var list : item.getTokenTransferListsList()) {
                for (final var transfer : list.getTransfersList()) {
                    if (transfer.getAccountID().getAccountNum() == expiredNum) {
                        assertTrue(
                                transfer.getAmount() < 0,
                                "Transfer amount in token " + "transfer list from expired entity should be negative");
                    } else {
                        assertTrue(
                                transfer.getAmount() > 0,
                                "Transfer amount in token " + "transfer list to other entities should be positive");
                    }
                }
                for (final var transfer : list.getNftTransfersList()) {
                    assertEquals(expiredNum, transfer.getSenderAccountID().getAccountNum());
                    assertTrue(transfer.getReceiverAccountID().getAccountNum() > 1000);
                    assertTrue(transfer.getSerialNumber() > 0);
                }
            }
        }
    }

    /**
     * Validate the transferred amount from renewed entity is always negative and the received
     * amount is always positive in transfer list.
     *
     * @param renewalRecords list of renewal records from stream
     */
    private void validateRenewalRecords(List<TransactionRecord> renewalRecords) {
        for (final var item : renewalRecords) {
            assertTrue(item.getReceipt().getStatus().equals(SUCCESS));

            final var renewedNum = getEntityNumFromAutoRenewalMemo(item.getMemo());

            for (final var transfers : item.getTransferList().getAccountAmountsList()) {
                if (transfers.getAccountID().getAccountNum() == renewedNum) {
                    assertTrue(transfers.getAmount() < 0);
                }
                if (transfers.getAccountID().getAccountNum() == 98) {
                    assertTrue(transfers.getAmount() > 0);
                }
                if (transfers.getAccountID().getAccountNum() == 800) {
                    assertTrue(transfers.getAmount() > 0);
                }
                if (transfers.getAccountID().getAccountNum() == 801) {
                    assertTrue(transfers.getAmount() > 0);
                }
            }
            assertTrue(item.getTokenTransferListsList().isEmpty());
        }
    }

    /**
     * Gets list of expired and renewal records from record stream based on the memo.
     *
     * @param recordsWithSidecars list of records in stream
     * @param renewalRecords empty list that will be filled with renewal records
     * @param expiredRecords empty list that will be filled with expired records
     */
    private void getExpiryRecordsFrom(
            final List<RecordWithSidecars> recordsWithSidecars,
            final List<TransactionRecord> renewalRecords,
            final List<TransactionRecord> expiredRecords) {
        for (final var recordWithSidecars : recordsWithSidecars) {
            final var items = recordWithSidecars.recordFile().getRecordStreamItemsList();
            items.stream()
                    .filter(item -> item.getRecord().getMemo().contains(AUTO_RENEWAL_MEMO))
                    .forEach(item -> renewalRecords.add(item.getRecord()));

            items.stream()
                    .filter(item -> item.getRecord().getMemo().contains(AUTO_EXPIRY_MEMO))
                    .forEach(item -> expiredRecords.add(item.getRecord()));
        }
    }

    private Long getEntityNumFromAutoRenewalMemo(final String memo) {
        final var entity = memo.substring(memo.indexOf("0.0."), memo.indexOf(AUTO_RENEWAL_MEMO))
                .substring(4);
        return Long.valueOf(entity);
    }

    private Long getEntityNumFromAutoExpiryMemo(final String memo) {
        final var entity = memo.substring(memo.indexOf("0.0."), memo.indexOf(AUTO_EXPIRY_MEMO))
                .substring(4);
        return Long.valueOf(entity);
    }
}
