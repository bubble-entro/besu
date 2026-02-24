package org.hyperledger.besu.evm.precompile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
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

public class NativeMinterPrecompiledContractTest {

  private NativeMinterPrecompiledContract contract;
  private MessageFrame frame;
  private WorldUpdater worldUpdater;
  private MutableAccount precompileAccount;
  private MutableAccount recipientAccount;

  // Signatures (must match the contract)
  private static final Bytes OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("owner()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes INITIALIZED_SIGNATURE =
      Hash.keccak256(Bytes.of("initialized()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes INITIALIZE_OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("initializeOwner(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes TRANSFER_OWNERSHIP_SIGNATURE =
      Hash.keccak256(Bytes.of("transferOwnership(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes MINT_SIGNATURE =
      Hash.keccak256(Bytes.of("mint(address,uint256)".getBytes(UTF_8))).slice(0, 4);

  // Storage slots
  private static final UInt256 INIT_SLOT = UInt256.ZERO;
  private static final UInt256 OWNER_SLOT = UInt256.ONE;

  // Returns
  private static final Bytes FALSE = Bytes.fromHexString(
      "0x0000000000000000000000000000000000000000000000000000000000000000");
  private static final Bytes TRUE = Bytes.fromHexString(
      "0x0000000000000000000000000000000000000000000000000000000000000001");

  // Mock Addresses
  private static final Address SENDER_ADDRESS =
      Address.fromHexString("0x1111111111111111111111111111111111111111");
  private static final Address NEW_OWNER_ADDRESS =
      Address.fromHexString("0x2222222222222222222222222222222222222222");
  private static final Address RECIPIENT_ADDRESS =
      Address.fromHexString("0x3333333333333333333333333333333333333333");

  /** Left-pad a 20-byte Address into a 32-byte Bytes32 */
  private static Bytes32 padAddress(final Address address) {
    return Bytes32.leftPad(address.getBytes());
  }

  @BeforeEach
  void setup() {
    contract = new NativeMinterPrecompiledContract(new FrontierGasCalculator());
    frame = Mockito.mock(MessageFrame.class);
    worldUpdater = Mockito.mock(WorldUpdater.class);
    precompileAccount = Mockito.mock(MutableAccount.class);
    recipientAccount = Mockito.mock(MutableAccount.class);

    when(frame.getWorldUpdater()).thenReturn(worldUpdater);
    when(frame.getSenderAddress()).thenReturn(SENDER_ADDRESS);
    when(frame.isStatic()).thenReturn(false);

    // Default: precompile account always returned
    when(worldUpdater.getOrCreate(Address.NATIVE_MINTER)).thenReturn(precompileAccount);
    when(worldUpdater.getOrCreate(RECIPIENT_ADDRESS)).thenReturn(recipientAccount);

    // Default storage: uninitialised
    when(precompileAccount.getStorageValue(any(UInt256.class))).thenReturn(UInt256.ZERO);
    when(precompileAccount.getNonce()).thenReturn(0L);
  }

  // ================================================================
  // EMPTY INPUT
  // ================================================================

  @Test
  public void testEmptyInputReturnsHalt() {
    var result = contract.computePrecompile(Bytes.EMPTY, frame);
    assertThat(result.getHaltReason())
        .isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  // ================================================================
  // OWNER / INITIALIZED (read-only queries)
  // ================================================================

  @Test
  public void testOwnerReturnsStoredValue() {
    UInt256 ownerValue = UInt256.fromBytes(padAddress(SENDER_ADDRESS));
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(ownerValue);

    var result = contract.computePrecompile(OWNER_SIGNATURE, frame);
    assertThat(result.output()).isEqualTo(ownerValue);
  }

  @Test
  public void testInitializedReturnsZeroWhenNotInitialized() {
    var result = contract.computePrecompile(INITIALIZED_SIGNATURE, frame);
    assertThat(result.output()).isEqualTo(UInt256.ZERO);
  }

  @Test
  public void testInitializedReturnsOneAfterInit() {
    when(precompileAccount.getStorageValue(INIT_SLOT)).thenReturn(UInt256.ONE);
    var result = contract.computePrecompile(INITIALIZED_SIGNATURE, frame);
    assertThat(result.output()).isEqualTo(UInt256.ONE);
  }

  // ================================================================
  // INITIALIZE OWNER
  // ================================================================

  @Test
  public void testInitializeOwnerSuccess() {
    Bytes calldata = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, padAddress(SENDER_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(TRUE);
    verify(precompileAccount).setStorageValue(OWNER_SLOT, UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
    verify(precompileAccount).setStorageValue(INIT_SLOT, UInt256.ONE);
    verify(precompileAccount).incrementNonce();
  }

  @Test
  public void testInitializeOwnerFailsWhenAlreadyInitialized() {
    // Mark as already initialized
    when(precompileAccount.getStorageValue(INIT_SLOT))
        .thenReturn(UInt256.fromBytes(TRUE));

    Bytes calldata = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, padAddress(SENDER_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
    verify(precompileAccount, never()).setStorageValue(OWNER_SLOT, UInt256.fromBytes(padAddress(SENDER_ADDRESS)));
  }

  @Test
  public void testInitializeOwnerFailsWithZeroAddress() {
    Bytes calldata = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, padAddress(Address.ZERO));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testInitializeOwnerBlockedInStaticCall() {
    when(frame.isStatic()).thenReturn(true);
    Bytes calldata = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, padAddress(SENDER_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);

    // Static calls fall through to the "function not found" branch
    assertThat(result.getHaltReason())
        .isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  // ================================================================
  // TRANSFER OWNERSHIP
  // ================================================================

  @Test
  public void testTransferOwnershipSuccess() {
    // Set SENDER_ADDRESS as current owner
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    Bytes calldata = Bytes.concatenate(TRANSFER_OWNERSHIP_SIGNATURE, padAddress(NEW_OWNER_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(TRUE);
    verify(precompileAccount).setStorageValue(OWNER_SLOT, UInt256.fromBytes(padAddress(NEW_OWNER_ADDRESS)));
  }

  @Test
  public void testTransferOwnershipFailsIfNotOwner() {
    // Set a different address as current owner
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(NEW_OWNER_ADDRESS)));

    Bytes calldata = Bytes.concatenate(TRANSFER_OWNERSHIP_SIGNATURE, padAddress(NEW_OWNER_ADDRESS));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testTransferOwnershipFailsWithZeroAddress() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    Bytes calldata = Bytes.concatenate(TRANSFER_OWNERSHIP_SIGNATURE, padAddress(Address.ZERO));
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  // ================================================================
  // MINT
  // ================================================================

  @Test
  public void testMintSuccess() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    UInt256 mintAmount = UInt256.valueOf(1000L);
    Bytes calldata = Bytes.concatenate(
        MINT_SIGNATURE,
        padAddress(RECIPIENT_ADDRESS),
        mintAmount
    );
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(TRUE);
    verify(recipientAccount).incrementBalance(Wei.of(mintAmount));
  }

  @Test
  public void testMintFailsIfNotOwner() {
    // Different owner
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(NEW_OWNER_ADDRESS)));

    UInt256 mintAmount = UInt256.valueOf(1000L);
    Bytes calldata = Bytes.concatenate(
        MINT_SIGNATURE,
        padAddress(RECIPIENT_ADDRESS),
        mintAmount
    );
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
    verify(recipientAccount, never()).incrementBalance(any(Wei.class));
  }

  @Test
  public void testMintFailsWithZeroAmount() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    Bytes calldata = Bytes.concatenate(
        MINT_SIGNATURE,
        padAddress(RECIPIENT_ADDRESS),
        UInt256.ZERO
    );
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testMintFailsToZeroAddress() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    UInt256 mintAmount = UInt256.valueOf(1000L);
    Bytes calldata = Bytes.concatenate(
        MINT_SIGNATURE,
        padAddress(Address.ZERO),
        mintAmount
    );
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(FALSE);
  }

  @Test
  public void testMintLargeAmount() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    // Mint 1 million ETH worth of Wei
    UInt256 largeAmount = UInt256.valueOf(1_000_000L).multiply(UInt256.valueOf(10L).pow(18));
    Bytes calldata = Bytes.concatenate(
        MINT_SIGNATURE,
        padAddress(RECIPIENT_ADDRESS),
        largeAmount
    );
    var result = contract.computePrecompile(calldata, frame);

    assertThat(result.output()).isEqualTo(TRUE);
    verify(recipientAccount).incrementBalance(Wei.of(largeAmount));
  }

  // ================================================================
  // GAS REQUIREMENTS
  // ================================================================

  @Test
  public void testGasRequirementForReadOnlyFunctions() {
    assertThat(contract.gasRequirement(OWNER_SIGNATURE)).isEqualTo(1000L);
    assertThat(contract.gasRequirement(INITIALIZED_SIGNATURE)).isEqualTo(1000L);
  }

  @Test
  public void testGasRequirementForWriteFunctions() {
    Bytes mintInput = Bytes.concatenate(MINT_SIGNATURE, padAddress(RECIPIENT_ADDRESS), UInt256.ONE);
    assertThat(contract.gasRequirement(mintInput)).isEqualTo(2000L);

    Bytes initInput = Bytes.concatenate(INITIALIZE_OWNER_SIGNATURE, padAddress(SENDER_ADDRESS));
    assertThat(contract.gasRequirement(initInput)).isEqualTo(2000L);
  }

  // ================================================================
  // UNKNOWN FUNCTION SELECTOR
  // ================================================================

  @Test
  public void testUnknownFunctionSelectorReturnsHalt() {
    Bytes unknownSelector = Bytes.fromHexString("0xdeadbeef");
    var result = contract.computePrecompile(unknownSelector, frame);
    assertThat(result.getHaltReason())
        .isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  // ================================================================
  // BOUND CHECKING — SHORT CALLDATA CRASH VECTORS
  // ================================================================

  @Test
  public void testMintShortCalldataReturnsFalse() {
    when(precompileAccount.getStorageValue(OWNER_SLOT))
        .thenReturn(UInt256.fromBytes(padAddress(SENDER_ADDRESS)));

    // Only send the selector + partial address (not enough for slice(12,20) + slice(32))
    Bytes shortCalldata = Bytes.concatenate(MINT_SIGNATURE, Bytes.of(0x01));

    var result = contract.computePrecompile(shortCalldata, frame);
    assertThat(result.output()).isEqualTo(FALSE);
  }
}
