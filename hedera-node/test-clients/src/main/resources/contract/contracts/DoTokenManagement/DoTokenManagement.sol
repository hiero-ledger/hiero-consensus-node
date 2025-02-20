// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./hip-206/IHederaTokenService.sol";

contract DoTokenManagement {
    address HTS_ADDRESS = address(0x167);

    function transferTokenUnitFromToOthers( 
        address token, 
        address from,
        address to
    ) external {
        bool success;
        bytes memory result;
        // Send a unit from (potentially) a different address (will fail w/o an approval or ContractID key)
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.transferToken.selector, token, from, to, int64(1)));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function transferSerialNo1FromToOthers( 
        address token, 
        address from,
        address to
    ) external {
        bool success;
        bytes memory result;
        // Send a serial no from (potentially) a different address (will fail w/o an approval or ContractID key)
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.transferNFT.selector, token, from, to, int64(1)));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function transferTokenUnitsFromToOthers( 
        address token, 
        address fromA,
        address toA,
        address fromB,
        address toB
    ) external {
        bool success;
        bytes memory result;

        // Send units from (potentially) two other addresses (will fail w/o approvals or ContractID keys)
        address[] memory accountIds = new address[](4);
        accountIds[0] = fromA;
        accountIds[1] = toA;
        accountIds[2] = fromB;
        accountIds[3] = toB;
        int64[] memory amounts = new int64[](4);
        amounts[0] = int64(-1);
        amounts[1] = int64(1);
        amounts[2] = int64(-1);
        amounts[3] = int64(1);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.transferTokens.selector, token, accountIds, amounts));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function transferNFTSerialNos2And3ToFromToOthers( 
        address token, 
        address fromA,
        address toA,
        address fromB,
        address toB
    ) external {
        bool success;
        bytes memory result;

        // Send serial nos from (potentially) two other addresses (will fail w/o approvals or ContractID keys)
        address[] memory senders = new address[](2);
        address[] memory receivers = new address[](2);
        senders[0] = fromA;
        senders[1] = fromB;
        receivers[0] = toA;
        receivers[1] = toB;
        int64[] memory serialNos = new int64[](2);
        serialNos[0] = int64(2);
        serialNos[1] = int64(3);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.transferNFTs.selector, token, senders, receivers, serialNos));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function transferViaThresholdContractKey(
        address token, 
        address controlledSpender,
        address beneficiary
    ) external {
        bool success;
        bytes memory result;

        // Send a unit from another account by virtue of our contract address
        address[] memory accountIds = new address[](2);
        accountIds[0] = controlledSpender;
        accountIds[1] = beneficiary;
        int64[] memory amounts = new int64[](2);
        amounts[0] = int64(-1);
        amounts[1] = int64(1);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.transferTokens.selector, token, accountIds, amounts));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function manageEverythingForFungible(
        address token, 
        address associatedAccount
    ) external {
        bool success;
        bytes memory result;

        // Do some global fungible token management 
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.pauseToken.selector, token));
        require(success);
        require(abi.decode(result, (int32)) == 22);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.unpauseToken.selector, token));
        require(success);
        require(abi.decode(result, (int32)) == 22);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.mintToken.selector, token, uint64(1), new bytes[](0)));
        require(success);
        require(abi.decode(result, (int32)) == 22);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.burnToken.selector, token, uint64(1), new int64[](0)));
        require(success);
        require(abi.decode(result, (int32)) == 22);
  
        // And now do account-specific fungible token management
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.grantTokenKyc.selector, token, associatedAccount));
        require(success);
        require(abi.decode(result, (int32)) == 22);
        // Send a unit to wipe later (assuming this contract is the treasury)
        address[] memory accountIds = new address[](2);
        accountIds[0] = address(this);
        accountIds[1] = associatedAccount;
        int64[] memory amounts = new int64[](2);
        amounts[0] = int64(-1);
        amounts[1] = int64(1);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.transferTokens.selector, token, accountIds, amounts));
        require(success);
        require(abi.decode(result, (int32)) == 22);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.freezeToken.selector, token, associatedAccount));
        require(success);
        require(abi.decode(result, (int32)) == 22);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.unfreezeToken.selector, token, associatedAccount));
        require(success);
        require(abi.decode(result, (int32)) == 22);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.wipeTokenAccount.selector, token, associatedAccount, uint32(1)));
        require(success);
        require(abi.decode(result, (int32)) == 22);
        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.revokeTokenKyc.selector, token, associatedAccount));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function justBurnFungible(address token) external {
        bool success;
        bytes memory result;

        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.burnToken.selector, token, uint64(1), new int64[](0)));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function justWipeFungible(
        address token,
        address associatedAccount
    ) external {
        bool success;
        bytes memory result;

        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.wipeTokenAccount.selector, token, associatedAccount, uint32(1)));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function justGrantKyc(
        address token,
        address associatedAccount
    ) external {
        bool success;
        bytes memory result;

        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.grantTokenKyc.selector, token, associatedAccount));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function justRevokeKyc(
        address token,
        address associatedAccount
    ) external {
        bool success;
        bytes memory result;

        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.revokeTokenKyc.selector, token, associatedAccount));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }

    function justFreezeAccount(
        address token,
        address associatedAccount
    ) external {
        bool success;
        bytes memory result;

        (success, result) = HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.freezeToken.selector, token, associatedAccount));
        require(success);
        require(abi.decode(result, (int32)) == 22);
    }
}
