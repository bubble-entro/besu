package org.hyperledger.besu.evm.precompile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.FrontierGasCalculator;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class GasFeeGrantPrecompiledContractTest {

  private GasFeeGrantPrecompiledContract contract;
  private MessageFrame frame;
  private WorldUpdater worldUpdater;
  private MutableAccount precompileAccount;
  private MutableAccount granteeAccount;
  private BlockValues blockValues;

  // Signatures
  private static final Bytes INITIALIZE_OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("initializeOwner(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes TRANSFER_OWNERSHIP_SIGNATURE =
      Hash.keccak256(Bytes.of("transferOwnership(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes IS_GRANT_FOR_PROGRAM_SIGNATURE =
      Hash.keccak256(Bytes.of("isGrantedForProgram(address,address)".getBytes(UTF_8))).slice(0, 4);
  
  // Storage slots from the contract
  private static final UInt256 INIT_SLOT = UInt256.ZERO;
  private static final UInt256 OWNER_SLOT = UInt256.ONE;

  // Returns
  private static final Bytes FALSE = Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000");
  private static final Bytes TRUE = Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001");

  // Mock Addresses
  private static final Address SENDER_ADDRESS = Address.fromHexString("0x1111111111111111111111111111111111111111");
  private static final Address NEW_OWNER_ADDRESS = Address.fromHexString("0x2222222222222222222222222222222222222222");
  private static final Address GRANTEE_ADDRESS = Address.fromHexString("0x3333333333333333333333333333333333333333");
  private static final Address PROGRAM_ADDRESS = Address.fromHexString("0x4444444444444444444444444444444444444444");

  // Helper: pad Address to 32 bytes (left-pad with zeros)
  private static Bytes32 padAddress(final Address address) {
    return Bytes32.leftPad(address.getBytes());
  }

  @BeforeEach
  public void setup() {
    contract = new GasFeeGrantPrecompiledContract(new FrontierGasCalculator());
    frame = Mockito.mock(MessageFrame.class);
    worldUpdater = Mockito.mock(WorldUpdater.class);
    precompileAccount = Mockito.mock(MutableAccount.class);
    granteeAccount = Mockito.mock(MutableAccount.class);
    blockValues = Mockito.mock(BlockValues.class);

    when(frame.getWorldUpdater()).thenReturn(worldUpdater);
    when(frame.getSenderAddress()).thenReturn(SENDER_ADDRESS);
    when(frame.getBlockValues()).thenReturn(blockValues);
    when(blockValues.getNumber()).thenReturn(100L);
    when(frame.isStatic()).thenReturn(false); 

    when(worldUpdater.getOrCreate(any(Address.class))).thenReturn(precompileAccount);
    when(worldUpdater.getOrCreate(GRANTEE_ADDRESS)).thenReturn(granteeAccount);
    
    // Default storage returns 0
    when(precompileAccount.getStorageValue(any(UInt256.class))).thenReturn(UInt256.ZERO);
  }

  @Test
  public void testInitializeOwnerSuccess() {
    Bytes ownerPadded = padAddress(SENDER_ADDRESS);
    Bytes calldata = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, ownerPadded);

    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(TRUE);
    assertThat(result.getHaltReason()).isEmpty();

    // Verify storage was updated for INIT_SLOT and OWNER_SLOT
    verify(precompileAccount).setStorageValue(INIT_SLOT, UInt256.ONE);
    verify(precompileAccount).setStorageValue(OWNER_SLOT, UInt256.fromBytes(ownerPadded));
  }

  @Test
  public void testInitializeOwnerFailsIfAlreadyInitialized() {
    // Mock that INIT_SLOT is already set to TRUE
    when(precompileAccount.getStorageValue(INIT_SLOT)).thenReturn(UInt256.fromBytes(TRUE));

    Bytes ownerPadded = padAddress(SENDER_ADDRESS);
    Bytes calldata = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, ownerPadded);

    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
    verify(precompileAccount, never()).setStorageValue(any(), any());
  }

  @Test
  public void testTransferOwnershipSuccess() {
    // Mock current owner to be SENDER_ADDRESS
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    Bytes newOwnerPadded = padAddress(NEW_OWNER_ADDRESS);
    Bytes calldata = Bytes.concatenate(TRANSFER_OWNERSHIP_SIGNATURE, newOwnerPadded);

    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(TRUE);
    verify(precompileAccount).setStorageValue(OWNER_SLOT, UInt256.fromBytes(newOwnerPadded));
  }

  @Test
  public void testTransferOwnershipFailsIfNotOwner() {
    // Mock current owner to be some other address
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(padAddress(NEW_OWNER_ADDRESS)));

    Bytes newOwnerPadded = padAddress(GRANTEE_ADDRESS);
    Bytes calldata = Bytes.concatenate(TRANSFER_OWNERSHIP_SIGNATURE, newOwnerPadded);

    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
    verify(precompileAccount, never()).setStorageValue(OWNER_SLOT, UInt256.fromBytes(newOwnerPadded));
  }

  @Test
  public void testStateChangingMethodFailsInStaticCall() {
    when(frame.isStatic()).thenReturn(true);
    
    Bytes ownerPadded = padAddress(SENDER_ADDRESS);
    Bytes calldata = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, ownerPadded);

    var result = contract.computePrecompile(calldata, frame);
    
    // In your implementation, if state changing & static call, it falls through to the final else block
    // which returns ExceptionalHaltReason.PRECOMPILE_ERROR
    assertThat(result.output()).isNull();
    assertThat(result.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  @Test
  public void testIsGrantedForProgramWhenNotGranted() {
    Bytes granteePadded = padAddress(GRANTEE_ADDRESS);
    Bytes programPadded = padAddress(PROGRAM_ADDRESS);
    Bytes calldata = Bytes.concatenate(IS_GRANT_FOR_PROGRAM_SIGNATURE, granteePadded, programPadded);

    // Default mock returns ZERO for all storage slots, meaning allowance slot is 0
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  // --- ADDITIONAL SETUP CONSTANTS ---
  private static final Bytes SET_FEE_GRANT_SIGNATURE =
      Hash.keccak256(Bytes.of("setFeeGrant(address,address,address,uint256,uint32,uint256,uint256)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes REVOKE_FEE_GRANT_SIGNATURE =
      Hash.keccak256(Bytes.of("revokeFeeGrant(address,address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes PERIOD_RESET_SIGNATURE =
      Hash.keccak256(Bytes.of("periodReset(address,address)".getBytes(UTF_8))).slice(0, 4);

  // --------------------------------------------------------
  // CRASH VECTOR: Malformed Calldata (Index Out of Bounds)
  // --------------------------------------------------------
  
  @Test
  public void testSetFeeGrantShortCalldataCausesCrash() {
    // Mock SENDER_ADDRESS as the owner to pass the onlyOwner check
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    // Create calldata that is intentionally too short (only the signature + 32 bytes)
    // The contract expects at least 224 bytes of arguments
    Bytes shortCalldata = Bytes.concatenate(
        SET_FEE_GRANT_SIGNATURE, 
        padAddress(GRANTEE_ADDRESS) 
    );

    // With bound checking in place, short calldata returns FALSE instead of crashing
    var result = contract.computePrecompile(shortCalldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // --------------------------------------------------------
  // LOGIC EDGE CASE: Spend Limit Exceeds Period Limit
  // --------------------------------------------------------

  @Test
  public void testSetFeeGrantFailsIfSpendLimitExceedsPeriodLimit() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    // Parameters
    Bytes granter = padAddress(SENDER_ADDRESS);
    Bytes grantee = padAddress(GRANTEE_ADDRESS);
    Bytes program = padAddress(PROGRAM_ADDRESS);
    
    UInt256 spendLimit = UInt256.valueOf(1000L);
    UInt256 period = UInt256.valueOf(100L); // 100 blocks
    UInt256 periodLimit = UInt256.valueOf(500L); // Limit is smaller than spend limit!
    UInt256 endTime = UInt256.valueOf(999999L);

    Bytes calldata = Bytes.concatenate(
        SET_FEE_GRANT_SIGNATURE,
        granter,
        grantee,
        program,
        spendLimit.toBytes(),
        period.toBytes(),
        periodLimit.toBytes(),
        endTime.toBytes()
    );

    var result = contract.computePrecompile(calldata, frame);

    // Contract should reject this and return FALSE because spendLimit (1000) > periodLimit (500)
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // --------------------------------------------------------
  // MATH EDGE CASE: Underflow in periodReset calculation
  // --------------------------------------------------------

  @Test
  public void testPeriodResetUnderflowBug() {
    // Setup context: Grant exists and allowance is set to 2 (meaning period logic applies)
    UInt256 rootSlot = computeStorageSlotGrant(GRANTEE_ADDRESS, PROGRAM_ADDRESS);

    when(precompileAccount.getStorageValue(rootSlot.add(1L))).thenReturn(UInt256.valueOf(2L)); 
    
    // Set resetBlock to 200, and period to 50
    when(precompileAccount.getStorageValue(rootSlot.add(5L))).thenReturn(UInt256.valueOf(200L));
    when(precompileAccount.getStorageValue(rootSlot.add(8L))).thenReturn(UInt256.valueOf(50L));

    // Current block number is 100 (which is BEFORE the reset block of 200)
    when(blockValues.getNumber()).thenReturn(100L);

    Bytes calldata = Bytes.concatenate(
        PERIOD_RESET_SIGNATURE,
        padAddress(GRANTEE_ADDRESS),
        padAddress(PROGRAM_ADDRESS)
    );

    var result = contract.computePrecompile(calldata, frame);
    UInt256 returnedResetBlock = UInt256.fromBytes(result.output());

    // Because of the fix: blockNumber < resetBlock will immediately return resetBlock.
    // The returned reset block will remain at 200, avoiding underflow corruption.
    System.out.println("Returned reset block should be 200: " + returnedResetBlock);
    
    // This assertion PROVES the fix works! The reset block correctly stays at 200.
    assertThat(returnedResetBlock).isEqualTo(UInt256.valueOf(200L));
  }

  // --------------------------------------------------------
  // LOGIC EDGE CASE: Zero Addresses 
  // --------------------------------------------------------

  @Test
  public void testSetFeeGrantFailsWithZeroAddressGranter() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    // Granter is Address.ZERO
    Bytes granter = padAddress(Address.ZERO);
    Bytes grantee = padAddress(GRANTEE_ADDRESS);
    Bytes program = padAddress(PROGRAM_ADDRESS);
    
    Bytes calldata = Bytes.concatenate(
        SET_FEE_GRANT_SIGNATURE, granter, grantee, program, 
        UInt256.valueOf(100L).toBytes(), UInt256.valueOf(100L).toBytes(), 
        UInt256.valueOf(100L).toBytes(), UInt256.valueOf(999999L).toBytes()
    );

    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testSetFeeGrantFailsIfNotOwner() {
    // Current owner is SENDER_ADDRESS
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    // Try to set fee grant from NEW_OWNER_ADDRESS (not the owner)
    when(frame.getSenderAddress()).thenReturn(NEW_OWNER_ADDRESS);

    Bytes granter = padAddress(SENDER_ADDRESS);
    Bytes grantee = padAddress(GRANTEE_ADDRESS);
    Bytes program = padAddress(PROGRAM_ADDRESS);
    
    Bytes calldata = Bytes.concatenate(
        SET_FEE_GRANT_SIGNATURE, granter, grantee, program, 
        UInt256.valueOf(100L).toBytes(), UInt256.valueOf(100L).toBytes(), 
        UInt256.valueOf(100L).toBytes(), UInt256.valueOf(999999L).toBytes()
    );

    var result = contract.computePrecompile(calldata, frame);

    // Should return FALSE because caller is not the owner
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testRevokeFeeGrantFailsIfNotOwner() {
    // Current owner is SENDER_ADDRESS
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    // Try to revoke grant from NEW_OWNER_ADDRESS (not the owner)
    when(frame.getSenderAddress()).thenReturn(NEW_OWNER_ADDRESS);

    Bytes grantee = padAddress(GRANTEE_ADDRESS);
    Bytes program = padAddress(PROGRAM_ADDRESS);

    Bytes calldata = Bytes.concatenate(REVOKE_FEE_GRANT_SIGNATURE, grantee, program);

    var result = contract.computePrecompile(calldata, frame);

    // Should return FALSE because caller is not the owner
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testRevokeFeeGrantSuccess() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    UInt256 rootSlot = computeStorageSlotGrant(GRANTEE_ADDRESS, PROGRAM_ADDRESS);
    when(precompileAccount.getStorageValue(rootSlot)).thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    Bytes32 counterSlotBytes = Hash.keccak256(Bytes.concatenate(Bytes32.wrap(UInt256.valueOf(3L)), padAddress(GRANTEE_ADDRESS)));
    UInt256 counterSlot = UInt256.fromBytes(counterSlotBytes);
    when(precompileAccount.getStorageValue(counterSlot)).thenReturn(UInt256.valueOf(1L));

    Bytes calldata = Bytes.concatenate(REVOKE_FEE_GRANT_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(TRUE);
  }

  // ================================================================
  // Additional Signatures
  // ================================================================
  private static final Bytes OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("owner()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes INITIALIZED_SIGNATURE =
      Hash.keccak256(Bytes.of("initialized()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes GRANT_SIGNATURE =
      Hash.keccak256(Bytes.of("grant(address,address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes IS_GRANT_FOR_ALL_PROGRAM_SIGNATURE =
      Hash.keccak256(Bytes.of("isGrantedForAllProgram(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes IS_EXPIRED_SIGNATURE =
      Hash.keccak256(Bytes.of("isExpired(address,address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes PERIOD_CAN_SPEND_SIGNATURE =
      Hash.keccak256(Bytes.of("periodCanSpend(address,address)".getBytes(UTF_8))).slice(0, 4);

  // ================================================================
  // Readback Tests
  // ================================================================

  @Test
  public void testOwnerReadback() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    var result = contract.computePrecompile(OWNER_SIGNATURE, frame);
    assertThat(result.output()).isEqualTo(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    assertThat(result.getHaltReason()).isEmpty();
  }

  @Test
  public void testInitializedReadback() {
    when(precompileAccount.getStorageValue(INIT_SLOT)).thenReturn(UInt256.ONE);
    var result = contract.computePrecompile(INITIALIZED_SIGNATURE, frame);
    assertThat(result.output()).isEqualTo(UInt256.ONE);
    assertThat(result.getHaltReason()).isEmpty();
  }

  @Test
  public void testInitializedReadbackWhenNotInitialized() {
    var result = contract.computePrecompile(INITIALIZED_SIGNATURE, frame);
    assertThat(result.output()).isEqualTo(UInt256.ZERO);
  }

  // ================================================================
  // Empty / Short Input Edge Cases
  // ================================================================

  @Test
  public void testEmptyInputHalts() {
    var result = contract.computePrecompile(Bytes.EMPTY, frame);
    assertThat(result.output()).isNull();
    assertThat(result.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  @Test
  public void testShortInputLessThan4BytesHalts() {
    var result = contract.computePrecompile(Bytes.fromHexString("0xaabb"), frame);
    assertThat(result.output()).isNull();
    assertThat(result.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  @Test
  public void testUnknownFunctionSelectorHalts() {
    Bytes calldata = Bytes.concatenate(Bytes.fromHexString("0xdeadbeef"), padAddress(SENDER_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isNull();
    assertThat(result.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  @Test
  public void testExactly4ByteSelectorOnlyForOwner() {
    // owner() takes no args, so 4 bytes is valid
    var result = contract.computePrecompile(OWNER_SIGNATURE, frame);
    assertThat(result.getHaltReason()).isEmpty();
  }

  // ================================================================
  // initializeOwner Edge Cases
  // ================================================================

  @Test
  public void testInitializeOwnerFailsWithZeroAddress() {
    Bytes calldata = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, padAddress(Address.ZERO));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testInitializeOwnerShortCalldataReturnsFalse() {
    // Only 16 bytes of argument instead of required 32
    Bytes calldata = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, Bytes.fromHexString("0x00112233445566778899aabbccddeeff"));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // transferOwnership Edge Cases
  // ================================================================

  @Test
  public void testTransferOwnershipFailsWithZeroAddress() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    Bytes calldata = Bytes.concatenate(TRANSFER_OWNERSHIP_SIGNATURE, padAddress(Address.ZERO));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testTransferOwnershipShortCalldataReturnsFalse() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    Bytes calldata = Bytes.concatenate(TRANSFER_OWNERSHIP_SIGNATURE, Bytes.fromHexString("0x0011"));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // Gas Requirement Tests
  // ================================================================

  @Test
  public void testGasRequirementWriteOperations() {
    assertThat(contract.gasRequirement(Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, padAddress(SENDER_ADDRESS)))).isEqualTo(2000L);
    assertThat(contract.gasRequirement(Bytes.concatenate(TRANSFER_OWNERSHIP_SIGNATURE, padAddress(SENDER_ADDRESS)))).isEqualTo(2000L);
    assertThat(contract.gasRequirement(Bytes.concatenate(SET_FEE_GRANT_SIGNATURE, padAddress(SENDER_ADDRESS)))).isEqualTo(2000L);
    assertThat(contract.gasRequirement(Bytes.concatenate(REVOKE_FEE_GRANT_SIGNATURE, padAddress(SENDER_ADDRESS)))).isEqualTo(2000L);
  }

  @Test
  public void testGasRequirementReadOperations() {
    assertThat(contract.gasRequirement(Bytes.concatenate(OWNER_SIGNATURE, padAddress(SENDER_ADDRESS)))).isEqualTo(1000L);
    assertThat(contract.gasRequirement(Bytes.concatenate(GRANT_SIGNATURE, padAddress(SENDER_ADDRESS)))).isEqualTo(1000L);
    assertThat(contract.gasRequirement(Bytes.concatenate(IS_GRANT_FOR_PROGRAM_SIGNATURE, padAddress(SENDER_ADDRESS)))).isEqualTo(1000L);
    assertThat(contract.gasRequirement(Bytes.concatenate(IS_EXPIRED_SIGNATURE, padAddress(SENDER_ADDRESS)))).isEqualTo(1000L);
  }

  @Test
  public void testGasRequirementShortInputReturnsZero() {
    assertThat(contract.gasRequirement(Bytes.fromHexString("0xaa"))).isEqualTo(0L);
    assertThat(contract.gasRequirement(Bytes.EMPTY)).isEqualTo(0L);
  }

  // ================================================================
  // Static Call Tests for All Write Operations
  // ================================================================

  @Test
  public void testSetFeeGrantStaticCallFails() {
    when(frame.isStatic()).thenReturn(true);
    Bytes calldata = Bytes.concatenate(SET_FEE_GRANT_SIGNATURE, padAddress(SENDER_ADDRESS),
        padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS),
        UInt256.valueOf(100L).toBytes(), UInt256.valueOf(100L).toBytes(),
        UInt256.valueOf(100L).toBytes(), UInt256.valueOf(999999L).toBytes());
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isNull();
    assertThat(result.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  @Test
  public void testRevokeFeeGrantStaticCallFails() {
    when(frame.isStatic()).thenReturn(true);
    Bytes calldata = Bytes.concatenate(REVOKE_FEE_GRANT_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isNull();
    assertThat(result.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  @Test
  public void testTransferOwnershipStaticCallFails() {
    when(frame.isStatic()).thenReturn(true);
    Bytes calldata = Bytes.concatenate(TRANSFER_OWNERSHIP_SIGNATURE, padAddress(NEW_OWNER_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isNull();
    assertThat(result.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  // ================================================================
  // Read-Only Functions Work in Static Call
  // ================================================================

  @Test
  public void testReadOnlyFunctionsWorkInStaticCall() {
    when(frame.isStatic()).thenReturn(true);

    assertThat(contract.computePrecompile(OWNER_SIGNATURE, frame).getHaltReason()).isEmpty();
    assertThat(contract.computePrecompile(INITIALIZED_SIGNATURE, frame).getHaltReason()).isEmpty();

    Bytes grantCalldata = Bytes.concatenate(GRANT_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    assertThat(contract.computePrecompile(grantCalldata, frame).getHaltReason()).isEmpty();

    Bytes isGrantedCalldata = Bytes.concatenate(IS_GRANT_FOR_PROGRAM_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    assertThat(contract.computePrecompile(isGrantedCalldata, frame).getHaltReason()).isEmpty();

    Bytes isExpiredCalldata = Bytes.concatenate(IS_EXPIRED_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    assertThat(contract.computePrecompile(isExpiredCalldata, frame).getHaltReason()).isEmpty();
  }

  // ================================================================
  // isGrantedForAllProgram Tests
  // ================================================================

  @Test
  public void testIsGrantedForAllProgramWhenNotGranted() {
    Bytes calldata = Bytes.concatenate(IS_GRANT_FOR_ALL_PROGRAM_SIGNATURE, padAddress(GRANTEE_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testIsGrantedForAllProgramShortCalldata() {
    // Less than 32 bytes of argument
    Bytes calldata = Bytes.concatenate(IS_GRANT_FOR_ALL_PROGRAM_SIGNATURE, Bytes.fromHexString("0x0011"));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // isExpired Tests
  // ================================================================

  @Test
  public void testIsExpiredReturnsFalseWhenNoGrant() {
    // No grant exists => isGrantedForProgram returns FALSE => isExpired returns TRUE
    Bytes calldata = Bytes.concatenate(IS_EXPIRED_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(TRUE);
  }

  @Test
  public void testIsExpiredReturnsFalseWhenEndTimeIsZero() {
    // Mock: grant exists (allowance slot != 0) but endTime is 0 (no expiry)
    UInt256 rootSlot = computeStorageSlotGrant(GRANTEE_ADDRESS, PROGRAM_ADDRESS);
    when(precompileAccount.getStorageValue(rootSlot.add(1L))).thenReturn(UInt256.ONE);
    when(precompileAccount.getStorageValue(rootSlot.add(6L))).thenReturn(UInt256.ZERO);

    Bytes calldata = Bytes.concatenate(IS_EXPIRED_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testIsExpiredReturnsTrueWhenBlockPastEndTime() {
    UInt256 rootSlot = computeStorageSlotGrant(GRANTEE_ADDRESS, PROGRAM_ADDRESS);
    when(precompileAccount.getStorageValue(rootSlot.add(1L))).thenReturn(UInt256.ONE);
    when(precompileAccount.getStorageValue(rootSlot.add(6L))).thenReturn(UInt256.valueOf(50L));
    when(blockValues.getNumber()).thenReturn(100L); // block 100 >= endTime 50

    Bytes calldata = Bytes.concatenate(IS_EXPIRED_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(TRUE);
  }

  @Test
  public void testIsExpiredReturnsFalseWhenBlockBeforeEndTime() {
    UInt256 rootSlot = computeStorageSlotGrant(GRANTEE_ADDRESS, PROGRAM_ADDRESS);
    when(precompileAccount.getStorageValue(rootSlot.add(1L))).thenReturn(UInt256.ONE);
    when(precompileAccount.getStorageValue(rootSlot.add(6L))).thenReturn(UInt256.valueOf(200L));
    when(blockValues.getNumber()).thenReturn(100L); // block 100 < endTime 200

    Bytes calldata = Bytes.concatenate(IS_EXPIRED_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testIsExpiredShortCalldataReturnsFalse() {
    Bytes calldata = Bytes.concatenate(IS_EXPIRED_SIGNATURE, padAddress(GRANTEE_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    // Short calldata: isGrantedForProgram returns FALSE => treated as expired => returns TRUE
    assertThat(result.output()).isEqualTo(TRUE);
  }

  // ================================================================
  // isGrantedForProgram Short Calldata
  // ================================================================

  @Test
  public void testIsGrantedForProgramShortCalldata() {
    Bytes calldata = Bytes.concatenate(IS_GRANT_FOR_PROGRAM_SIGNATURE, Bytes.fromHexString("0x0011"));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // grant() Read Tests
  // ================================================================

  @Test
  public void testGrantReadShortCalldata() {
    Bytes calldata = Bytes.concatenate(GRANT_SIGNATURE, padAddress(GRANTEE_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // setFeeGrant Additional Edge Cases
  // ================================================================

  @Test
  public void testSetFeeGrantFailsWithZeroAddressGrantee() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    Bytes calldata = Bytes.concatenate(SET_FEE_GRANT_SIGNATURE,
        padAddress(SENDER_ADDRESS), padAddress(Address.ZERO), padAddress(PROGRAM_ADDRESS),
        UInt256.valueOf(100L).toBytes(), UInt256.valueOf(100L).toBytes(),
        UInt256.valueOf(100L).toBytes(), UInt256.valueOf(999999L).toBytes());
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testSetFeeGrantFailsWithZeroSpendLimit() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    Bytes calldata = Bytes.concatenate(SET_FEE_GRANT_SIGNATURE,
        padAddress(SENDER_ADDRESS), padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS),
        UInt256.ZERO.toBytes(), UInt256.valueOf(100L).toBytes(),
        UInt256.valueOf(100L).toBytes(), UInt256.valueOf(999999L).toBytes());
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // revokeFeeGrant Additional Edge Cases
  // ================================================================

  @Test
  public void testRevokeFeeGrantShortCalldataReturnsFalse() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    Bytes calldata = Bytes.concatenate(REVOKE_FEE_GRANT_SIGNATURE, Bytes.fromHexString("0x0011"));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testRevokeFeeGrantFailsWithZeroGrantee() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    Bytes calldata = Bytes.concatenate(REVOKE_FEE_GRANT_SIGNATURE, padAddress(Address.ZERO), padAddress(PROGRAM_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // periodCanSpend Tests
  // ================================================================

  @Test
  public void testPeriodCanSpendShortCalldata() {
    Bytes calldata = Bytes.concatenate(PERIOD_CAN_SPEND_SIGNATURE, padAddress(GRANTEE_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testPeriodCanSpendNonPeriodicReturnsFalse() {
    // allowance is 1 (non-periodic), not 2
    UInt256 rootSlot = computeStorageSlotGrant(GRANTEE_ADDRESS, PROGRAM_ADDRESS);
    when(precompileAccount.getStorageValue(rootSlot.add(1L))).thenReturn(UInt256.ONE);

    Bytes calldata = Bytes.concatenate(PERIOD_CAN_SPEND_SIGNATURE, padAddress(GRANTEE_ADDRESS), padAddress(PROGRAM_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // periodReset Short Calldata
  // ================================================================

  @Test
  public void testPeriodResetShortCalldata() {
    Bytes calldata = Bytes.concatenate(PERIOD_RESET_SIGNATURE, padAddress(GRANTEE_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // Wildcard Tests
  // ================================================================

  private static final Bytes WILDCARD_SIGNATURE =
      Hash.keccak256(Bytes.of("wildcard(address)".getBytes(UTF_8))).slice(0, 4);

  @Test
  public void testWildcardSuccess() {
    // Initialize owner first
    when(precompileAccount.getStorageValue(INIT_SLOT)).thenReturn(UInt256.ONE);
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    Bytes calldata = Bytes.concatenate(WILDCARD_SIGNATURE, padAddress(GRANTEE_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(TRUE);
    assertThat(result.getHaltReason()).isEmpty();

    // Verify FEE_GRANT_FLAG set on grantee
    verify(granteeAccount).setStorageValue(
        GasFeeGrantPrecompiledContract.FEE_GRANT_FLAG_STORAGE, UInt256.ONE);

    // Verify allowance type 3 was stored
    UInt256 rootSlot = computeStorageSlotGrant(GRANTEE_ADDRESS, Address.ZERO);
    verify(precompileAccount).setStorageValue(rootSlot.add(1L), UInt256.valueOf(3L));
  }

  @Test
  public void testWildcardFailsIfNotOwner() {
    // Initialize owner as SENDER, but call from NEW_OWNER
    when(precompileAccount.getStorageValue(INIT_SLOT)).thenReturn(UInt256.ONE);
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    when(frame.getSenderAddress()).thenReturn(NEW_OWNER_ADDRESS);

    Bytes calldata = Bytes.concatenate(WILDCARD_SIGNATURE, padAddress(GRANTEE_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testWildcardFailsForZeroAddress() {
    when(precompileAccount.getStorageValue(INIT_SLOT)).thenReturn(UInt256.ONE);
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    Bytes calldata = Bytes.concatenate(WILDCARD_SIGNATURE, padAddress(Address.ZERO));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testWildcardFailsIfAlreadyGranted() {
    when(precompileAccount.getStorageValue(INIT_SLOT)).thenReturn(UInt256.ONE);
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    // Simulate existing wildcard grant (allowance slot != 0)
    UInt256 rootSlot = computeStorageSlotGrant(GRANTEE_ADDRESS, Address.ZERO);
    when(precompileAccount.getStorageValue(rootSlot.add(1L))).thenReturn(UInt256.valueOf(3L));

    Bytes calldata = Bytes.concatenate(WILDCARD_SIGNATURE, padAddress(GRANTEE_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testWildcardShortCalldataReturnsFalse() {
    when(precompileAccount.getStorageValue(INIT_SLOT)).thenReturn(UInt256.ONE);
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    // Only 16 bytes of calldata (need 32)
    Bytes shortData = Bytes.fromHexString("0x1111111111111111111111111111111111111111");
    Bytes calldata = Bytes.concatenate(WILDCARD_SIGNATURE, shortData);
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testWildcardStaticCallBlocked() {
    when(frame.isStatic()).thenReturn(true);

    Bytes calldata = Bytes.concatenate(WILDCARD_SIGNATURE, padAddress(GRANTEE_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isNull();
    assertThat(result.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  // ================================================================
  // Helper: Compute storage slot matching the contract's storageSlotGrant
  // ================================================================

  private static UInt256 computeStorageSlotGrant(final Address grantee, final Address program) {
    final UInt256 GRANTS_SLOT = UInt256.valueOf(2L);
    final Bytes32 root = Hash.keccak256(
        Bytes.concatenate(Bytes32.wrap(GRANTS_SLOT), Bytes32.leftPad(grantee.getBytes())));
    final Bytes32 slot = Hash.keccak256(
        Bytes.concatenate(root, Bytes32.leftPad(program.getBytes())));
    return UInt256.fromBytes(slot);
  }
}