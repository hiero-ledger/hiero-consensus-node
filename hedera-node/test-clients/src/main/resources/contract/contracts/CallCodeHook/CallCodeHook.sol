// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./IHieroAccountAllowanceHook.sol";
import "./IHieroHook.sol";

/// Minimal interface for the HTS system contract at 0x167
interface IHederaTokenServiceMinimal {
    /// Associates `account` with `token`. Returns Hedera response code (int64).
    function associateToken(address account, address token) external returns (int64);
}

/// @title CallCodeHook
/// @notice Hook that uses CALLCODE to associate tokens.
/// @dev CALLCODE is a deprecated opcode similar to DELEGATECALL.
///      It executes the target's code in the caller's storage context,
///      but msg.sender is the immediate caller (not preserved from original).
///      This opcode is deprecated and should not be used in production.
contract CallCodeHook is IHieroAccountAllowanceHook {
    // HIP-1195 special hook address
    address constant HOOK_ADDR = address(uint160(0x16d));

    // HTS precompile (system contract) address
    IHederaTokenServiceMinimal constant HTS =
    IHederaTokenServiceMinimal(address(uint160(0x167)));

    /// Allowlist of payers permitted to use this hook
    mapping(address => bool) public isAllowedSender; // slot 0

    /// Event emitted to show CALLCODE execution context
    event CallCodeAttempt(
        bool success,
        int64 response,
        address seenThis,     // address(this) as seen inside the callcode
        address seenSender    // msg.sender as seen inside the callcode
    );

    /// Hook entrypoint
    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable override returns (bool) {
        // Ensure we're actually running as a hook (0x16d)
        require(address(this) == HOOK_ADDR, "Only callable as a hook");

        // If the payer isn't allowlisted, reject
        if (!isAllowedSender[msg.sender]) {
            return false;
        }

        // Hook owner is the account whose allowances we're customizing
        address owner = context.owner;

        // For every token referenced in the proposal, attempt association via CALLCODE
        _exerciseCallCode(owner, proposedTransfers.direct.tokens);
        _exerciseCallCode(owner, proposedTransfers.customFee.tokens);

        // Signal "allow"; the network will proceed
        return true;
    }

    function _exerciseCallCode(
        address owner,
        IHieroAccountAllowanceHook.TokenTransferList[] memory lists
    ) internal {
        for (uint256 i = 0; i < lists.length; i++) {
            address token = lists[i].token;

            // --- CALLCODE (self) ---
            // CALLCODE is deprecated but similar to DELEGATECALL:
            // - Storage operations affect the caller's storage
            // - msg.sender is the immediate caller (not preserved)
            // - address(this) is the caller's address
            bytes memory payload = abi.encodeWithSelector(this._associateImpl.selector, owner, token);
            (bool ok, bytes memory ret) = _callcode(address(this), payload);
            (int64 response, address thisAddr, address snd) = _decodeAttempt(ok, ret);
            emit CallCodeAttempt(ok, response, thisAddr, snd);
        }
    }

    // The implementation we invoke via CALLCODE.
    // Returns both the HTS response code and what the callee "saw" for this/sender.
    function _associateImpl(address owner, address token)
    external
    payable
    returns (int64 response, address seenThis, address seenSender)
    {
        response = HTS.associateToken(owner, token);
        seenThis = address(this);
        seenSender = msg.sender;
    }

    // Inline assembly CALLCODE trampoline
    function _callcode(address target, bytes memory payload)
    internal
    returns (bool success, bytes memory returnData)
    {
        assembly {
            let p := add(payload, 0x20)
            let plen := mload(payload)

            // callcode(gas, target, value=0, inOffset, inSize, outOffset, outSize)
            let ok := callcode(gas(), target, 0, p, plen, 0, 0)

            let size := returndatasize()
            returnData := mload(0x40)
            mstore(returnData, size)
            mstore(0x40, add(add(returnData, 0x20), size))
            returndatacopy(add(returnData, 0x20), 0, size)

            success := ok
        }
    }

    function _decodeAttempt(bool ok, bytes memory ret)
    internal
    pure
    returns (int64 response, address thisAddr, address snd)
    {
        if (ok && ret.length >= 96) {
            (response, thisAddr, snd) = abi.decode(ret, (int64, address, address));
        } else {
            response = 0;
            thisAddr = address(0);
            snd = address(0);
        }
    }
}

