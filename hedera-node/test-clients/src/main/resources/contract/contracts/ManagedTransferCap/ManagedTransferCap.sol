// SPDX-License-Identifier: Apache-2.0
//
// ManagedTransferCap - HIP-1195 Account Allowance Hook (Advanced Example)
//
// A two-phase hook that enforces an owner-managed transfer cap. The account
// owner sets a remaining allowance via HookStoreTransaction. Each transfer
// that references this hook is checked against the remaining cap in allowPre()
// and deducted in allowPost(). When the cap reaches zero, all transfers are
// rejected until the owner increases it.
//
// Uses PRE_POST_TX_ALLOWANCE_HOOK (allowPre + allowPost) to demonstrate the
// two-phase hook dispatch pattern:
//   - allowPre: verify the transfer fits within the remaining cap
//   - allowPost: deduct the transfer amount from the remaining cap
//
// ============================================================================
// SECURITY: Production Hardening Notes
// ============================================================================
//
// 1. HOOK_ADDR GUARD: Both hook functions check address(this) == HOOK_ADDR.
//    This ensures they only run inside a Hedera hook EVM frame at address 0x16d.
//
// 2. GLOBAL CAP: This demo uses a single cap for all token types and HBAR.
//    A production version should use per-token caps via Solidity mappings and
//    EvmHookMappingEntries in HookStoreTransaction.
//
// 3. receiver_sig_required: This hook is designed for accounts with
//    receiver_sig_required=true, which forces ALL inbound transfers through
//    the hook. Without it, senders can bypass the cap by omitting the hook
//    reference in their FungibleHookCall.
//
// 4. OVERFLOW: The contract uses unchecked subtraction in allowPost() since
//    allowPre() already verified totalCredit <= remaining. In production,
//    add explicit overflow checks.
//
// ============================================================================
pragma solidity 0.8.34;

// ----------------------------------------------------------------------------
// HIP-1195 interfaces - inlined
// Source: https://github.com/hiero-ledger/hiero-consensus-node/tree/v0.72.0/
//         hedera-node/test-clients/src/main/resources/contract/contracts/
// ----------------------------------------------------------------------------

/// @notice Base hook interface - provides the HookContext struct passed to all hook functions.
interface IHieroHook {
    struct HookContext {
        address owner;    // Entity (account or contract) that owns this hook
        uint256 txnFee;   // Transaction fee charged for the triggering transaction
        uint256 gasCost;  // Gas allocated for this hook execution
        string memo;      // Transaction memo
        bytes data;       // ABI-encoded calldata from EvmHookCall.data
    }
}

/// @notice Interface for the ACCOUNT_ALLOWANCE_HOOK extension point (HIP-1195).
interface IHieroAccountAllowanceHook {
    struct AccountAmount {
        address account;
        int64 amount;
    }

    struct NftTransfer {
        address sender;
        address receiver;
        int64 serialNo;
    }

    struct TokenTransferList {
        address token;
        AccountAmount[] adjustments;
        NftTransfer[] nftTransfers;
    }

    struct Transfers {
        AccountAmount[] hbarAdjustments;
        TokenTransferList[] tokens;
    }

    struct ProposedTransfers {
        Transfers direct;
        Transfers customFee;
    }

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable returns (bool);
}

// ----------------------------------------------------------------------------
// ManagedTransferCap
// ----------------------------------------------------------------------------

/// @title  ManagedTransferCap
/// @notice Two-phase hook that enforces an owner-managed cap on inbound transfers.
///
///         Storage layout:
///           slot 0x00 = remaining allowance (uint256)
///
///         Setup (hook owner writes initial cap via HookStoreTransaction):
///           1. Choose a cap value (e.g., 500 token units).
///           2. Encode as uint256, apply minimal byte representation.
///           3. Write to slot 0x00 via HookStoreTransaction.
///
///         Transfer flow (PRE_POST_TX_ALLOWANCE_HOOK):
///           1. allowPre() reads remaining cap from slot 0x00.
///           2. Calculates total credit to the hook owner from ProposedTransfers.
///           3. If credit <= remaining, returns true (approved).
///           4. allowPost() deducts the credit from remaining, writes back.
///
///         Cap management:
///           The hook owner can increase or reset the cap at any time via
///           HookStoreTransaction. No contract redeployment needed.
contract ManagedTransferCap is IHieroAccountAllowanceHook {

    address constant HOOK_ADDR = address(uint160(0x16d));

    /// @notice Pre-transfer phase: check if the transfer fits within the remaining cap.
    function allowPre(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable returns (bool) {
        require(address(this) == HOOK_ADDR, "only callable as hook");

        // Read remaining cap from slot 0x00
        uint256 remaining;
        assembly { remaining := sload(0) }

        // No cap set or cap depleted: reject
        if (remaining == 0) return false;

        // Calculate total credit to the hook owner
        uint256 totalCredit = _calculateCredit(context.owner, proposedTransfers);

        // Approve if within cap
        return totalCredit <= remaining;
    }

    /// @notice Post-transfer phase: deduct the transfer amount from the remaining cap.
    function allowPost(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable returns (bool) {
        require(address(this) == HOOK_ADDR, "only callable as hook");

        // Read remaining cap
        uint256 remaining;
        assembly { remaining := sload(0) }

        // Calculate credit and deduct
        uint256 totalCredit = _calculateCredit(context.owner, proposedTransfers);
        uint256 newRemaining = remaining - totalCredit;

        // Write updated cap
        assembly { sstore(0, newRemaining) }

        return true;
    }

    /// @notice Not used - this contract uses PRE_POST_TX_ALLOWANCE_HOOK (allowPre + allowPost).
    ///         Included to satisfy the interface. Reverts if called directly.
    function allow(
        IHieroHook.HookContext calldata,
        ProposedTransfers memory
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "only callable as hook");
        return true;
    }

    /// @dev Sums all positive (credit) amounts for the owner across HBAR and token transfers.
    function _calculateCredit(
        address owner,
        ProposedTransfers memory proposed
    ) internal pure returns (uint256) {
        uint256 total = 0;

        // Sum HBAR credits
        AccountAmount[] memory hbarAdj = proposed.direct.hbarAdjustments;
        for (uint256 i = 0; i < hbarAdj.length; i++) {
            if (hbarAdj[i].account == owner && hbarAdj[i].amount > 0) {
                total += uint256(uint64(hbarAdj[i].amount));
            }
        }

        // Sum token credits across all token types
        TokenTransferList[] memory tokenLists = proposed.direct.tokens;
        for (uint256 i = 0; i < tokenLists.length; i++) {
            AccountAmount[] memory adj = tokenLists[i].adjustments;
            for (uint256 j = 0; j < adj.length; j++) {
                if (adj[j].account == owner && adj[j].amount > 0) {
                    total += uint256(uint64(adj[j].amount));
                }
            }
        }

        return total;
    }
}
