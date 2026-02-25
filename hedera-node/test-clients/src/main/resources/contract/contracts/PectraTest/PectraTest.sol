// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.7;

contract PectraTest {

    function callBls12() public returns (bytes memory response){
        (bool success, bytes memory res) = address(0x0b).call(abi.encode(bytes(
            "a2mgoyfkjyetwoeqf660842nm0i2unrdsvr2bxcvqcaguk4odwl0xsvb5mj0qjl08shz1tg1qkubaann68agvpazvdxhzclkc3s6so7ata8mc2qif2xu8rcgntmi8oqq94l52zmbkpvr0utl8u1gemkqcvl3rlai5ffc4jgs3nrs7bkwnhxtfhmu1vnx3tw16fkyf54l6u69vzax6crubjmb8xc6qi592391hzxk2q1mee11gtkqa528qfzd3yob"
        )));

        require(success == true);
        return res;
    }
}
