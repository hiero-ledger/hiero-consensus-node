// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.processors.PublicMessageProcessor;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.hevm.HevmPropagatedCallFailure;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.TODO;

// BESU imports
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.TangerineWhistleGasCalculator;

public abstract class CallManager {

    static ExceptionalHaltReason create(BEVM bevm, SB trace, boolean hasSalt ) {
        // Nested create contract call; so print the post-trace before the
        // nested call and reload the pre-trace state after call.
        String str = hasSalt ? "CREATE2" : "CREATE";
        preTraceCall(bevm,trace,str);
        if( bevm._sp < (hasSalt ? 4 : 3) ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        if( bevm._frame.isStatic() ) return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
        Address sender = hookSender(bevm._frame);
        Wei value = bevm.popWei();
        // Memory size for code and gas
        int off = bevm.popInt();
        int len = bevm.popInt();
        Bytes salt = hasSalt ? bevm.popBytes() : null;

        CodeV2 code = CodeV2.make(bevm._mem._mem,off,len);

        var senderAccount = bevm._frame.getWorldUpdater().getAccount(sender);
        if( value.compareTo(senderAccount.getBalance()) > 0 || bevm._frame.getDepth() >= 1024/*AbstractCustomCreateOperation.MAX_STACK_DEPTH*/)
            return bevm.push0();
        long senderNonce = senderAccount.getNonce();
        senderAccount.incrementNonce(); // Post increment

        Address recv_contract = hasSalt
            ? saltContract(sender,salt,code)
            : Address.contractAddress(sender, senderNonce);
        assert recv_contract != null;
        bevm.isWarm(recv_contract);  // Force contract address to be warmed up

        if( bevm._frame.getWorldUpdater() instanceof ProxyWorldUpdater proxyUpdater )
            proxyUpdater.setupInternalAliasedCreate(sender, recv_contract);

        // gas cost for making the contract
        long gas = bevm._gasCalc.txCreateCost() + // 32000
            bevm._gasCalc.initcodeCost(len) +     // Shanghai: words(544)*2 == 16 words *2 = 32
            bevm.memoryExpansionGasCost(off,len);
        if( hasSalt ) gas += bevm._gasCalc.createKeccakCost(len);

        return _abstractCall2(bevm,trace,str,
                              MessageFrame.Type.CONTRACT_CREATION,
                              gas,         // Gas charged
                              bevm._gas,   // Child stipend
                              false,       // Add passed-value stipend
                              recv_contract, recv_contract, sender,
                              value,       // Wei value passed along
                              Bytes.EMPTY, // No passed outgoing data
                              code,
                              false,       // Not static
                              0, 0,        // No return data
                              bevm._bonneville._create);
    }

    static final Bytes CREATE2_PREFIX = Bytes.fromHexString("0xFF");
    static private Address saltContract( Address sender, Bytes salt, CodeV2 code ) {
        // The original called kekkek256 *twice*, once on the code bytes, and
        // then again on the concatenated whole.  kekkek256 is a big part of
        // the profiles, and I have a theory that it is it not needed to be called
        // *twice*.  The exact salted value is tested in the
        // Create2OperationSuite and so cannot just be changed yet.

        // {FF, [sender 20bytes], [salt 32bytes], [code hash, 32bytes] }
        Bytes bytes = Bytes.concatenate(CREATE2_PREFIX, sender, salt, code.getCodeHash());
        Bytes32 hash = org.hyperledger.besu.crypto.Hash.keccak256(bytes);
        return Address.extract(hash);
    }

    // Custom Delegate Call 6->1
    static ExceptionalHaltReason customDelegateCall(BEVM bevm, SB trace) {
        // BasicCustomCall.executeChecked
        if( bevm._sp < 6 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        if( checkHookExec(bevm._frame) )
            return ExceptionalHaltReason.INVALID_OPERATION;
        var recv   = bevm._frame.getRecipientAddress();
        var sender = bevm._frame.getSenderAddress();
        long stipend = bevm.popLong();
        Address contract = bevm.popAddress();

        return _abstractCall(bevm, trace, "DELEGATE", stipend, recv, contract, sender, Wei.ZERO, bevm._frame.isStatic());
    }

    // Custom Delegate Call 6->1
    static ExceptionalHaltReason customStaticCall(BEVM bevm, SB trace) {
        // BasicCustomCall.executeChecked
        if( bevm._sp < 6 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        var sender = hookSender(bevm._frame);
        long stipend = bevm.popLong();
        Address recv_contract = bevm.popAddress();

        return _abstractCall(bevm, trace, "STATIC", stipend, recv_contract, recv_contract, sender, Wei.ZERO, true);
    }

    // Call Code - same as customCall except skips the custom mustBePresent check
    static ExceptionalHaltReason callCode(BEVM bevm, SB trace) {
        if( bevm._sp < 7 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        if( checkHookExec(bevm._frame) )
            return ExceptionalHaltReason.INVALID_OPERATION;
        var recv_send = hookSender(bevm._frame);
        long stipend = bevm.popLong();
        Address contract = bevm.popAddress();
        Wei value = bevm.popWei();

        return _abstractCall(bevm, trace, "CALL", stipend, recv_send, contract, recv_send, value, bevm._frame.isStatic());
    }

    // CustomCallOperation
    static ExceptionalHaltReason customCall(BEVM bevm, SB trace) {
        if( bevm._sp < 7) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        // child frame sender is this frames' recipient
        var sender = hookSender(bevm._frame);
        long stipend = bevm.popLong();
        Address recv_contract = bevm.popAddress();
        Wei value = bevm.popWei();

        return _abstractCall(bevm, trace, "CUSTCALL", stipend, recv_contract, recv_contract, sender, value, bevm._frame.isStatic());
    }

    /**
     *   Start a nested contract/call frame.  This code is shared by several
     *   callers, all of whom alter how <code>{reciever, contract, sender}
     *   </code> are chosen; other parameters are generally pulled from the
     *   stack or are static values.  Here <code>hook</code> means the
     *   *hooked* current frame recipient - the recipient after checking for
     *   Hedera hooks.  Also, <code>pop</code> means the value is popped from
     *   the stack.
     *   <pre>
     *   | CallType | Recipient   | Contract | Sender       |
     *   |----------|-------------|----------|--------------|
     *   | Created  | constructed | =recip   | hook         |
     *   | Delegate | _frame.recip| pop      | _frame.recip |
     *   | Static   | pop         | =recip   | hook         |
     *   | Normal   | hook        | pop      | =recip       |
     *   | Custom   | pop         | =recip   | hook         |
     *   </pre>
     *   A non-halting return can still be a *failed* (or successful)
     *   call.  A halting return halts this current frame execution.
     *   A non-halting return pushes a boolean onto the stack.
     *
     *   @param trace Not-null to trace execution
     *   @param str Used for tracing
     *   @param stipend Used to compute minimum gas for the child frame
     *   @param recipient recipient
     *   @param contract contract
     *   @param sender sender
     *   @param value passed Wei value
     *   @param isStatic the child frame is static
     *   @return null for non-halting return, or a {@see ExceptionHaltReason} otherwise.
     */
    static ExceptionalHaltReason _abstractCall(BEVM bevm, SB trace, String str, long stipend, Address recipient, Address contract, Address sender, Wei value, boolean isStatic ) {
        // Nested create contract call; so print the post-trace before the
        // nested call, and reload the pre-trace state after call.
        preTraceCall(bevm,trace,str);

        int srcOff = bevm.popInt();  // Outgoing data passed to child
        int srcLen = bevm.popInt();
        int dstOff = bevm.popInt();  // Incoming data received from child
        int dstLen = bevm.popInt();

        boolean hasValue = value.toUInt256() != UInt256.ZERO;

        if( bevm.mustBePresent(contract, hasValue) ) {
        //    FrameUtils.invalidAddressContext(bevm._frame).set(to, InvalidAddressContext.InvalidAddressType.InvalidCallTarget);
        //    return new OperationResult(cost, INVALID_SOLIDITY_ADDRESS);
            throw new TODO();
        }

        // CallOperation
        if( isStatic && hasValue )
            return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;

        // Not sure how this can be set
        Account contractAccount = bevm._frame.getWorldUpdater().get(contract);
        if( contractAccount!=null && contractAccount.hasDelegatedCode() )
            throw new TODO();

        // Get the bytecodes to execute; if we have a contract account, get
        // code from it
        CodeV2 code = CodeV2.make(contractAccount);

        // gas cost check.  As usual, the input values are capped at
        // Integer.MAX_VALUE, and the sum of a few of these will not overflow a
        // 64-bit integer, and large gas values will just fail the
        // gas-available check - hence no need for overflow math.
        long srcCost = bevm.memoryExpansionGasCost(srcOff, srcLen);
        long dstCost = bevm.memoryExpansionGasCost(dstOff, dstLen);
        long memCost = Math.max(srcCost, dstCost);
        long gas = bevm._gasCalc.callOperationBaseGasCost() + memCost;
        if( hasValue )
            gas += bevm._gasCalc.callValueTransferGasCost();
        if( (contractAccount == null || contractAccount.isEmpty()) && hasValue )
            gas += bevm._gasCalc.newAccountGasCost();
        if( (recipient == null || recipient.isEmpty()) && hasValue )
            throw new TODO();
        // Check the cold account cost, but do not charge
        if( bevm._gas < gas+ (isStatic ? bevm._gasCalc.getWarmStorageReadCost() : bevm._gasCalc.getColdAccountAccessCost()) )
            return ExceptionalHaltReason.INSUFFICIENT_GAS;

        // There is a 2nd gas check made here, with account possibly being warm
        // which is lower cost, so never fails...
        gas += bevm.isWarm(contract)
            ? bevm._gasCalc.getWarmStorageReadCost()
            : bevm._gasCalc.getColdAccountAccessCost();

        // If the call is sending more value than the account has or the
        // message frame is to deep return a failed call
        if( value.compareTo(bevm._recv.getBalance()) > 0 || bevm._frame.getDepth() >= 1024 )
            return bevm.push0();

        return _abstractCall2(bevm,trace,str,
                              MessageFrame.Type.MESSAGE_CALL,
                              gas,
                              stipend,
                              hasValue,
                              recipient, contract, sender,
                              value,
                              bevm._mem.asBytes(srcOff, srcLen),
                              code,
                              isStatic,
                              dstOff, dstLen,
                              bevm._bonneville._call );
    }

    static ExceptionalHaltReason _abstractCall2(BEVM bevm, SB trace, String str, MessageFrame.Type type, long gas, long stipend, boolean getsValueStipend, Address recipient, Address contract, Address sender, Wei value, Bytes src, CodeV2 code, boolean isStatic, int dstOff, int dstLen, PublicMessageProcessor msg ) {
        MessageFrame frame = bevm._frame;
        frame.clearReturnData();

        // Charge gas for call
        var halt = bevm.useGas(gas);
        if( halt != null ) return halt;

        // We unwind the TW back-door gas calc, and so need to make sure that's
        // what we got
        assert bevm._gasCalc instanceof TangerineWhistleGasCalculator;
        // gasAvailableForChildCall; this is the "all but 1/64th" computation
        long gasCap = Math.min(bevm._gas - (bevm._gas>>6), stipend);
        bevm._gas -= gasCap;
        frame.setGasRemaining(bevm._gas);

        long childGasStipend = gasCap;
        // Calls (not Create) with value get an additional stipend
        if( getsValueStipend )
            childGasStipend +=  2300L /*bevm._gasCalc.getAdditionalCallStipend()*/;

        // ----------------------------
        // child frame is added to frame stack via build method
        MessageFrame child = MessageFrame.builder()
            .parentMessageFrame(frame)
            .type(type)
            .initialGas(childGasStipend)
            .address(recipient)
            .contract(contract)
            .inputData(src)
            .sender(sender)
            .value(value)
            .apparentValue(value)
            .code(code)
            .isStatic(isStatic)
            .completer(child0 -> {} )
            .build();
        frame.setState(MessageFrame.State.CODE_SUSPENDED);

        // Frame lifetime management
        assert child.getState() == MessageFrame.State.NOT_STARTED;
        bevm._tracing.traceContextEnter(child);

        // More frame safety checks, also pre-compiled contracts
        msg.start(child, bevm._tracing);

        if( child.getState() == MessageFrame.State.CODE_EXECUTING ) {
            // ----------------------------
            // Recursively call
            bevm._bonneville.runToHalt(bevm,code,child,bevm._tracing);
            // ----------------------------
        }

        switch( child.getState() ) {
        case MessageFrame.State.CODE_SUSPENDED:    throw new TODO("Should not reach here");
        case MessageFrame.State.CODE_SUCCESS:      msg.codeSuccess(child, bevm._tracing);  break; // Sets COMPLETED_SUCCESS
        case MessageFrame.State.REVERT:            msg.revert(child);  break;
        case MessageFrame.State.COMPLETED_SUCCESS: break; // Precompiled sys contracts hit here
        case MessageFrame.State.COMPLETED_FAILED:  throw new TODO("cant find who sets this");
        case MessageFrame.State.EXCEPTIONAL_HALT:  halt = childHalted(frame,child); break;
        default: throw new TODO();
        }

        bevm._tracing.traceContextExit(child);
        child.getWorldUpdater().commit();
        child.getMessageFrameStack().removeFirst(); // Pop child frame

        // See AbstractCustomCreateOperation.complete
        bevm._mem.write(dstOff, child.getOutputData(), 0, dstLen);
        frame.setReturnData(child.getOutputData());
        frame.addLogs(child.getLogs());
        frame.addCreates(child.getCreates());
        frame.addSelfDestructs(child.getSelfDestructs());
        frame.incrementGasRefund(child.getGasRefund());

        bevm._gas += child.getRemainingGas(); // Recover leftover gas from child

        if( trace != null )
            System.out.println(trace.clear().p("RETURN ").p(str).nl());

        boolean success = child.getState()==MessageFrame.State.COMPLETED_SUCCESS;
        if( type == MessageFrame.Type.CONTRACT_CREATION )
            bevm.push( success ? child.getContractAddress() : null );
        else
            bevm.push( success ? 1 : 0);
        return halt;
    }

    private static ExceptionalHaltReason childHalted( MessageFrame parent, MessageFrame child ) {
        FrameUtils.exceptionalHalt(child);
        var haltReason = child.getExceptionalHaltReason().get();
        // Fairly obnoxious back-door signal to propagate a particular
        // failure 1 call level, or not.  From CustomMessageCallProcessor:324
        var maybeFailureToPropagate = FrameUtils.getAndClearPropagatedCallFailure(parent);
        if( maybeFailureToPropagate != HevmPropagatedCallFailure.NONE ) {
            assert maybeFailureToPropagate.exceptionalHaltReason().get() == haltReason;
            return haltReason;
        }
        // These propagate indefinitely, killing the code at the top level.
        if( haltReason == CustomExceptionalHaltReason.INVALID_CONTRACT_ID ||
            haltReason == CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS )
            return haltReason;
        return null;
    }


    private static boolean checkHookExec(MessageFrame frame) {
        if( !FrameUtils.isHookExecution(frame) ) return false;
        // isNotRedirectFromNativeEntity
        final var updater = (ProxyWorldUpdater) frame.getWorldUpdater();
        final var recipient = updater.getHederaAccount(frame.getRecipientAddress());
        return !recipient.isTokenFacade() && !recipient.isScheduleTxnFacade() && !recipient.isRegularAccount();
    }


    private static Address hookSender(MessageFrame frame) {
        return frame.getRecipientAddress().equals(HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS)
            ? FrameUtils.hookOwnerAddress(frame)
            : frame.getRecipientAddress();
    }


    public static void complete( MessageFrame frame, MessageFrame child ) {/*nothing*/}

    private static void preTraceCall( BEVM bevm, SB trace, String str) {
        if( trace == null ) return;
        System.out.println(bevm.postTrace(trace).nl().nl().p("CONTRACT ").p(str).nl());
        trace.clear();
    }

}
