// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;

import "./HederaTokenService.sol";
import "./NestedHTSTransferrer.sol";

contract HbarFeeCollector is HederaTokenService {

    NestedHTSTransferrer nestedHTSTransferrer;

    constructor(address transferrerContractAddress) public {
        nestedHTSTransferrer = NestedHTSTransferrer(transferrerContractAddress);
    }

    function feeDistributionAfterTransfer(
        address _tokenAddress,
        address _sender,
        address _tokenReceiver,
        address payable _hbarReceiver,
        int64 _tokenAmount,
        uint256 _hbarAmount) external {
        nestedHTSTransferrer.transfer(_tokenAddress, _sender, _tokenReceiver, _tokenAmount);
        _hbarReceiver.transfer(_hbarAmount);
    }
}
