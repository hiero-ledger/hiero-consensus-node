// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHederaTokenService.sol';

/// The interface for a transfer allowance lambda.
interface IHieroTransferAllowance {
    /// Combines HBAR and HTS asset transfers.
    struct Transfers {
        /// The HBAR transfers
        IHederaTokenService.TransferList hbar;
        /// The HTS token transfers
        IHederaTokenService.TokenTransferList tokens;
    }

    /// Combines the full proposed transfers for a Hiero transaction,
    /// including both its direct transfers and the implied HIP-18
    /// custom fee transfers.
    struct ProposedTransfers {
        /// The transaction's direct transfers
        Transfers direct;
        /// The transaction's assessed custom fees
        Transfers customFee;
    }

    /// Decides if the proposed transfers are allowed, optionally in
    /// the presence of additional context encoded by the transaction
    /// payer in the extra args.
    /// @param proposedTransfers The proposed transfers
    /// @param args The extra arguments
    /// @return true If the proposed transfers are allowed, false or revert otherwise
    function allow(ProposedTransfers memory proposedTransfers, bytes memory args) external payable returns (bool);
}
