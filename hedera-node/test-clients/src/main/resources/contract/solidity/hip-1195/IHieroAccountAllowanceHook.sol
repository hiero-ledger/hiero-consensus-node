// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHederaTokenService.sol';

/// The interface for a generic EVM hook.
interface IHieroHook {
    /// The context the hook is executing in
    struct HookContext {
        /// The address of the entity the hook is executing on behalf of
        address owner;
        /// The fee the transaction payer was charged for the triggering transaction
        uint256 txnFee;
        /// The gas cost the transaction payer was charged for specifically this hook
        uint256 gasCost;
        /// The memo of the triggering transaction
        string memo;
        /// Any extra call data passed to the hook
        bytes data;
    }
}

/// The interface for an account allowance hook invoked once before a CryptoTransfer.
interface IHieroAccountAllowanceHook {
    /// Combines HBAR and HTS asset transfers.
    struct Transfers {
        /// The HBAR transfers
        IHederaTokenService.TransferList hbar;
        /// The HTS token transfers
        IHederaTokenService.TokenTransferList[] tokens;
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
    /// payer in the extra calldata.
    /// @param context The context of the hook call
    /// @param proposedTransfers The proposed transfers
    /// @return true If the proposed transfers are allowed, false or revert otherwise
    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable returns (bool);
}

/// The interface for an account allowance hook invoked both before and after a CryptoTransfer.
interface IHieroAccountAllowancePrePostHook {
    /// Combines HBAR and HTS asset transfers.
    struct Transfers {
        /// The HBAR transfers
        IHederaTokenService.TransferList hbar;
        /// The HTS token transfers
        IHederaTokenService.TokenTransferList[] tokens;
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

    /// Decides if the proposed transfers are allowed BEFORE the CryptoTransfer
    /// business logic is performed, optionally in the presence of additional
    /// context encoded by the transaction payer in the extra calldata.
    /// @param context The context of the hook call
    /// @param proposedTransfers The proposed transfers
    /// @return true If the proposed transfers are allowed, false or revert otherwise
    function allowPre(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable returns (bool);

    /// Decides if the proposed transfers are allowed AFTER the CryptoTransfer
    /// business logic is performed, optionally in the presence of additional
    /// context encoded by the transaction payer in the extra calldata.
    /// @param context The context of the hook call
    /// @param proposedTransfers The proposed transfers
    /// @return true If the proposed transfers are allowed, false or revert otherwise
    function allowPost(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable returns (bool);
}