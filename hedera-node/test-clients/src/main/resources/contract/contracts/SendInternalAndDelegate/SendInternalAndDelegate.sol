// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract SendInternalAndDelegate {
    constructor() payable {}

    fallback() external payable {}

    receive() external payable {}

    function sendRepeatedlyTo(
        uint160 just_send_num,
        uint160 account_num,
        uint64 value
    ) public payable {
        /* First send "normally" */
        address payable beneficiary = payable(address(uint160(account_num)));
        beneficiary.transfer(value);

        /* Now send via the delegate */
        address just_send = address(uint160(just_send_num));
        (bool success, bytes memory data) = just_send.delegatecall(
            abi.encodeWithSignature("sendTo(uint160,uint64)", account_num, value)
        );
    }
}
