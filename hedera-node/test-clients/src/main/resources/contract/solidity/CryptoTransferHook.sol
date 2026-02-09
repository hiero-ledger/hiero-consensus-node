// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import "../IHieroAccountAllowanceHook.sol";
import "../IHieroHook.sol";

/// Minimal HTS precompile interface for cryptoTransfer V1.
/// V1 selector: 0x189a554c
/// Signature: cryptoTransfer((address,(address,int64)[],(address,address,int64)[])[])
interface IHederaTokenServiceV1 {
    struct AccountAmount {
        address accountID;
        int64 amount;
    }

    struct NftTransfer {
        address senderAccountID;
        address receiverAccountID;
        int64 serialNumber;
    }

    struct TokenTransferList {
        address token;
        AccountAmount[] transfers;
        NftTransfer[] nftTransfers;
    }

    function cryptoTransfer(TokenTransferList[] memory tokenTransfers)
        external
        returns (int64 responseCode);
}

/// Hook that performs a cryptoTransfer of fungible tokens between parties during allow().
/// The EvmHookCall.data must be abi.encode(address token, address receiver, int64 amount).
/// It transfers fungible tokens from the hook owner to the given receiver via the HTS
/// precompile's cryptoTransfer V1 function.
contract CryptoTransferHook is IHieroAccountAllowanceHook {
    // HTS precompile address
    IHederaTokenServiceV1 constant HTS =
        IHederaTokenServiceV1(address(uint160(0x167)));
    // HIP-1195 special hook address
    address constant HOOK_ADDR = address(uint160(0x16d));

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory /* proposedTransfers */
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");

        // Decode token address, receiver address, and amount from calldata
        (address token, address receiver, int64 amount) =
            abi.decode(context.data, (address, address, int64));

        // Build the fungible token transfer: owner sends, receiver receives
        IHederaTokenServiceV1.AccountAmount[] memory amounts =
            new IHederaTokenServiceV1.AccountAmount[](2);
        amounts[0] = IHederaTokenServiceV1.AccountAmount(context.owner, -amount);
        amounts[1] = IHederaTokenServiceV1.AccountAmount(receiver, amount);

        // Single token transfer list entry
        IHederaTokenServiceV1.TokenTransferList[] memory tokenTransfers =
            new IHederaTokenServiceV1.TokenTransferList[](1);
        tokenTransfers[0] = IHederaTokenServiceV1.TokenTransferList(
            token,
            amounts,
            new IHederaTokenServiceV1.NftTransfer[](0));

        // Execute the cryptoTransfer V1 via HTS precompile
        int64 rc = HTS.cryptoTransfer(tokenTransfers);
        require(rc == 22, "CryptoTransfer failed"); // 22 == HederaResponseCodes.SUCCESS

        return true;
    }
}
