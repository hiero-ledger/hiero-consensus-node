// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./IHieroAccountAllowanceHook.sol";
import "./IHieroHook.sol";

interface IHederaTokenServiceMinimal {
    function associateToken(address account, address token) external returns (int64);
}

/// @title StaticCallHook
/// @notice Hook that uses STATICCALL to prove read-only semantics.
/// @dev STATICCALL forbids any state changes. Attempting HTS.associateToken()
///      inside the static context will make the low-level call return success=false.
contract StaticCallHook is IHieroAccountAllowanceHook {
    address constant HOOK_ADDR = address(uint160(0x16d));
    IHederaTokenServiceMinimal constant HTS =
    IHederaTokenServiceMinimal(address(uint160(0x167)));

    mapping(address => bool) public isAllowedSender; // slot 0

    event StaticCallAttempt(
        bool success,
        int64 response,
        address seenThis,
        address seenSender
    );

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "Only callable as a hook");
        if (!isAllowedSender[msg.sender]) return false;

        address owner = context.owner;
        _exerciseStaticCall(owner, proposedTransfers.direct.tokens);
        _exerciseStaticCall(owner, proposedTransfers.customFee.tokens);

        // We never revert; signaling allow==true lets the test to proceed.
        return true;
    }

    function _exerciseStaticCall(
        address owner,
        IHieroAccountAllowanceHook.TokenTransferList[] memory lists
    ) internal {
        for (uint256 i = 0; i < lists.length; i++) {
            address token = lists[i].token;
            bytes memory payload = abi.encodeWithSelector(this._associateImpl.selector, owner, token);

            (bool ok, bytes memory ret) = address(this).staticcall(payload);
            // If ok=false (expected), decoding will fail → zeros; that's what we emit.
            (int64 response, address thisAddr, address snd) = _decodeAttempt(ok, ret);
            emit StaticCallAttempt(ok, response, thisAddr, snd);
        }
    }

    function _associateImpl(address owner, address token)
    external
    payable
    returns (int64 response, address seenThis, address seenSender)
    {
        response = HTS.associateToken(owner, token);
        seenThis = address(this);
        seenSender = msg.sender;
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