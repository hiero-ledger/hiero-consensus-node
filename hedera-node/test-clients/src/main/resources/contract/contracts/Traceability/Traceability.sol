// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;

contract Traceability {
    uint256 slot0;
    uint256 slot1;
    uint256 slot2;

    constructor (uint256 _slot0, uint256 _slot1, uint256 _slot2) {
        slot0 = _slot0;
        slot1 = _slot1;
        slot2 = _slot2;
    }

    function eetScenario1(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot1(55);

        Traceability contractB = Traceability(_contractBAddress);
        contractB.getSlot2();
        contractB.setSlot2(143);

        contractB.callAddressGetSlot0(_contractCAddress);
        contractB.callAddressSetSlot0(_contractCAddress, 0);

        contractB.callAddressGetSlot1(_contractCAddress);
        contractB.callAddressSetSlot1(_contractCAddress, 0);
    }

    function eetScenario2(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot1(55);

        this.callAddressGetSlot2(_contractBAddress);
        this.callAddressSetSlot2(_contractBAddress, 143);

        Traceability contractB = Traceability(_contractBAddress);
        contractB.delegateCallAddressGetSlot0(_contractCAddress);
        contractB.delegateCallAddressSetSlot0(_contractCAddress, 100);
        contractB.delegateCallAddressGetSlot1(_contractCAddress);
        contractB.delegateCallAddressSetSlot1(_contractCAddress, 0);
    }

    function eetScenario3(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot1(55252);

        this.delegateCallAddressGetSlot2(_contractCAddress);
        this.delegateCallAddressSetSlot2(_contractCAddress, 524);

        _contractBAddress.delegatecall(abi.encodeWithSignature("callAddressGetSlot0(address)", _contractCAddress));
        _contractBAddress.delegatecall(abi.encodeWithSignature("callAddressSetSlot0(address,uint256)", _contractCAddress, 54));
        _contractBAddress.delegatecall(abi.encodeWithSignature("callAddressGetSlot1(address)", _contractCAddress));
        _contractBAddress.delegatecall(abi.encodeWithSignature("callAddressSetSlot1(address,uint256)", _contractCAddress, 0));
    }

    function eetScenario4(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot0(3);
        this.getSlot1();
        this.setSlot1(4);

        this.delegateCallAddressGetSlot2(_contractBAddress);

        _contractBAddress.delegatecall(abi.encodeWithSignature("delegateCallAddressSetSlot0(address,uint256)", _contractCAddress, 55));
    }

    function eetScenario5(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot1(55252);

        Traceability contractB = Traceability(_contractBAddress);
        contractB.getSlot2();
        contractB.setSlot2(524);

        contractB.staticCallAddressGetSlot0(_contractCAddress);
        contractB.staticCallAddressGetSlot1(_contractCAddress);
    }

    function eetScenario6(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot1(4);

        this.delegateCallAddressGetSlot2(_contractBAddress);
        this.delegateCallAddressSetSlot2(_contractBAddress, 5);

        _contractBAddress.delegatecall(abi.encodeWithSignature("staticCallAddressGetSlot0(address)", _contractCAddress));
        _contractBAddress.delegatecall(abi.encodeWithSignature("staticCallAddressGetSlot1(address)", _contractCAddress));
    }

    function eetScenario9(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot1(55252);

        Traceability contractB = Traceability(_contractBAddress);
        contractB.getSlot2();
        contractB.setSlot2(524);

        Traceability contractC = Traceability(_contractCAddress);
        contractC.callToContractCForE2EScenario92();
    }

    function eetScenario10(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot1(4);

        this.callAddressGetSlot2(_contractBAddress);
        this.callAddressSetSlot2(_contractBAddress, 5);


        Traceability contractC = Traceability(_contractCAddress);
        try contractC.failingGettingAndSetting() {
            return;
        } catch Error(string memory) {
            return;
        } catch (bytes memory) {
            return;
        }
    }

    function eetScenario11(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot1(4);

        _contractBAddress.delegatecall("readAndWriteThenRevert()");

        Traceability contractC = Traceability(_contractCAddress);
        contractC.getSlot0();
        contractC.setSlot0(123);
        contractC.getSlot1();
        contractC.setSlot1(0);
    }


    //////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////

    // GETTERS AND SETTERS
    function getSlot0() external returns(uint256) {
        return slot0;
    }

    function setSlot0(uint256 _slot0) external {
        slot0 = _slot0;
    }

    function getSlot1() external returns(uint256) {
        return slot1;
    }

    function setSlot1(uint256 _slot1) external {
        slot1 = _slot1;
    }

    function getSlot2() external returns(uint256) {
        return slot2;
    }

    function setSlot2(uint256 _slot2) external {
        slot2 = _slot2;
    }


    // CALLS TO ADDRESS
    function callAddressGetSlot0(address _address) external {
        _address.call(abi.encodeWithSignature("getSlot0()"));
    }

    function callAddressSetSlot0(address _address, uint256 slot) external {
        _address.call(abi.encodeWithSignature("setSlot0(uint256)", slot));
    }

    function callAddressGetSlot1(address _address) external {
        _address.call(abi.encodeWithSignature("getSlot1()"));
    }

    function callAddressSetSlot1(address _address, uint256 slot) external {
        _address.call(abi.encodeWithSignature("setSlot1(uint256)", slot));
    }

    function callAddressGetSlot2(address _address) external {
        _address.call(abi.encodeWithSignature("getSlot2()"));
    }
    function callAddressSetSlot2(address _address, uint256 slot) external {
        _address.call(abi.encodeWithSignature("setSlot2(uint256)", slot));
    }



    // DELEGATE CALLS TO ADDRESS
    function delegateCallAddressGetSlot0(address _address) external {
        _address.delegatecall(abi.encodeWithSignature("getSlot0()"));
    }
    function delegateCallAddressSetSlot0(address _address, uint256 slot) external {
        _address.delegatecall(abi.encodeWithSignature("setSlot0(uint256)", slot));
    }

    function delegateCallAddressGetSlot1(address _address) external {
        _address.delegatecall(abi.encodeWithSignature("getSlot1()"));
    }
    function delegateCallAddressSetSlot1(address _address, uint256 slot) external {
        _address.delegatecall(abi.encodeWithSignature("setSlot1(uint256)", slot));
    }

    function delegateCallAddressGetSlot2(address _address) external {
        _address.delegatecall(abi.encodeWithSignature("getSlot2()"));
    }

    function delegateCallAddressSetSlot2(address _address, uint256 slot) external {
        _address.delegatecall(abi.encodeWithSignature("setSlot2(uint256)", slot));
    }



    // STATIC CALLS TO ADDRESS
    function staticCallAddressGetSlot0(address _address) external {
        _address.staticcall(abi.encodeWithSignature("getSlot0()"));
    }
    function staticCallAddressGetSlot1(address _address) external {
        _address.staticcall(abi.encodeWithSignature("getSlot1()"));
    }



    // CALLS FOR REVERTING SCENARIOS
    function failingGettingAndSetting() external {
        this.getSlot0();
        this.setSlot0(12);
        this.getSlot1();
        this.setSlot1(0);
        revert();
    }

    function readAndWriteThenRevert() external {
        this.getSlot2();
        this.setSlot2(10);
        revert();
    }

    function callToContractCForE2EScenario92() external {
        this.getSlot0();
        this.setSlot0(55);
        this.getSlot1();
        this.setSlot1(155);
        revert();
    }
}
