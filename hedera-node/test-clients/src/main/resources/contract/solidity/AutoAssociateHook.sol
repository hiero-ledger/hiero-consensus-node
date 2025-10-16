// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import "./IHieroAccountAllowanceHook.sol";
import "./IHieroHook.sol";

/// Minimal interface for the HTS system contract at 0x167.
/// We only need associateToken() for this hook.
interface IHederaTokenServiceMinimal {
    /// Associates `account` with `token`. Returns Hedera response code (int64).
    function associateToken(address account, address token) external returns (int64);
}

/// @title AutoAssociateHook
/// @notice Keeps an allowlist (mapping) of permitted senders. If the hook
///         is executed by an allowlisted sender, it attempts to associate
///         the hook owner (context.owner) to every token mentioned in the
///         proposed transfers; then returns true. Otherwise returns false.
///
/// NOTE: When run as a hook, this bytecode executes at the special address
///       0x16d, and msg.sender is the txn payer that referenced the hook.
///       (See HIP‑1195 for the EVM environment.)
contract AutoAssociateHook is IHieroAccountAllowanceHook {
    // HIP‑1195 special hook address
    address constant HOOK_ADDR = address(uint160(0x16d));

    // HTS precompile (system contract) address
    IHederaTokenServiceMinimal constant HTS =
    IHederaTokenServiceMinimal(address(uint160(0x167)));

    /// Allowlist of payers permitted to use this hook
    mapping(address => bool) public isAllowedSender;

    /// The hook entrypoint
    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external override payable returns (bool) {
        // Ensure we’re actually running as a hook (0x16d)
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");

        // If the payer isn’t allowlisted, reject
        if (!isAllowedSender[msg.sender]) {
            return false;
        }

        // Hook owner is the account whose allowances we’re customizing
        address owner = context.owner;

        // Attempt associations for all tokens referenced in this transfer proposal.
        // We don't check whether the owner is directly involved; we just associate
        // owner to every token mentioned to make the transfer more likely to succeed.
        _associateAll(owner, proposedTransfers.direct.tokens);
        _associateAll(owner, proposedTransfers.customFee.tokens);

        return true;
    }

    function _associateAll(
        address owner,
        IHieroAccountAllowanceHook.TokenTransferList[] memory lists
    ) internal {
        for (uint256 i = 0; i < lists.length; i++) {
            // Ignore the response code; if already associated, HTS returns a non‑success code,
            // which is fine (this hook only signals allow/deny to the network).
            HTS.associateToken(owner, lists[i].token);
        }
    }
}