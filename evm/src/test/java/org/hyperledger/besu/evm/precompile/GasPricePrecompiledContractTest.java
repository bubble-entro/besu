package org.hyperledger.besu.evm.precompile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;
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

public class GasPricePrecompiledContractTest {

  private GasPricePrecompiledContract contract;
  private MessageFrame frame;
  private WorldUpdater worldUpdater;
  private MutableAccount precompileAccount;

  private static final Bytes OWNER_SIG =
      Hash.keccak256(Bytes.of("owner()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes INIT_OWNER_SIG =
      Hash.keccak256(Bytes.of("initializeOwner(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes TRANSFER_SIG =
      Hash.keccak256(Bytes.of("transferOwnership(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes GASPRICE_SIG =
      Hash.keccak256(Bytes.of("gasPrice()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes SET_GASPRICE_SIG =
      Hash.keccak256(Bytes.of("setGasPrice(uint256)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes ENABLE_SIG =
      Hash.keccak256(Bytes.of("enable()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes DISABLE_SIG =
      Hash.keccak256(Bytes.of("disable()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes STATUS_SIG =
      Hash.keccak256(Bytes.of("status()".getBytes(UTF_8))).slice(0, 4);

  private static final UInt256 INIT_SLOT = UInt256.ZERO;
  private static final UInt256 OWNER_SLOT = UInt256.ONE;
  private static final UInt256 STATUS_SLOT = UInt256.valueOf(2L);
  private static final UInt256 GASPRICE_SLOT = UInt256.valueOf(3L);

  private static final Bytes FALSE = Bytes.fromHexString(
      "0x0000000000000000000000000000000000000000000000000000000000000000");
  private static final Bytes TRUE = Bytes.fromHexString(
      "0x0000000000000000000000000000000000000000000000000000000000000001");

  private static final Address SENDER =
      Address.fromHexString("0x1111111111111111111111111111111111111111");
  private static final Address OTHER =
      Address.fromHexString("0x2222222222222222222222222222222222222222");

  private static Bytes32 pad(final Address a) { return Bytes32.leftPad(a.getBytes()); }

  @BeforeEach
  void setup() {
    contract = new GasPricePrecompiledContract(new FrontierGasCalculator());
    frame = Mockito.mock(MessageFrame.class);
    worldUpdater = Mockito.mock(WorldUpdater.class);
    precompileAccount = Mockito.mock(MutableAccount.class);
    when(frame.getWorldUpdater()).thenReturn(worldUpdater);
    when(frame.getSenderAddress()).thenReturn(SENDER);
    when(frame.isStatic()).thenReturn(false);
    when(worldUpdater.getOrCreate(Address.GASPRICE)).thenReturn(precompileAccount);
    when(precompileAccount.getStorageValue(any(UInt256.class))).thenReturn(UInt256.ZERO);
    when(precompileAccount.getNonce()).thenReturn(0L);
  }

  // ---- Crash guards ----
  @Test void testEmptyInputHalts() {
    var r = contract.computePrecompile(Bytes.EMPTY, frame);
    assertThat(r.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }
  @Test void testShortInputHalts() {
    var r = contract.computePrecompile(Bytes.of(0x01, 0x02), frame);
    assertThat(r.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }
  @Test void testGasRequirementShortInput() {
    assertThat(contract.gasRequirement(Bytes.of(0x01))).isEqualTo(0L);
  }

  // ---- initializeOwner ----
  @Test void testInitializeOwnerSuccess() {
    var r = contract.computePrecompile(Bytes.concatenate(INIT_OWNER_SIG, pad(SENDER)), frame);
    assertThat(r.output()).isEqualTo(TRUE);
    verify(precompileAccount).setStorageValue(OWNER_SLOT, UInt256.fromBytes(pad(SENDER)));
    verify(precompileAccount).setStorageValue(INIT_SLOT, UInt256.ONE);
  }
  @Test void testInitializeOwnerFailsAlreadyInit() {
    when(precompileAccount.getStorageValue(INIT_SLOT)).thenReturn(UInt256.fromBytes(TRUE));
    var r = contract.computePrecompile(Bytes.concatenate(INIT_OWNER_SIG, pad(SENDER)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }
  @Test void testInitializeOwnerFailsZero() {
    var r = contract.computePrecompile(Bytes.concatenate(INIT_OWNER_SIG, pad(Address.ZERO)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }
  @Test void testInitializeOwnerShortCalldata() {
    var r = contract.computePrecompile(Bytes.concatenate(INIT_OWNER_SIG, Bytes.of(0x01)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- transferOwnership ----
  @Test void testTransferOwnershipSuccess() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(Bytes.concatenate(TRANSFER_SIG, pad(OTHER)), frame);
    assertThat(r.output()).isEqualTo(TRUE);
  }
  @Test void testTransferOwnershipNotOwner() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(OTHER)));
    var r = contract.computePrecompile(Bytes.concatenate(TRANSFER_SIG, pad(OTHER)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }
  @Test void testTransferOwnershipShortCalldata() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(Bytes.concatenate(TRANSFER_SIG, Bytes.of(0x01)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- setGasPrice ----
  @Test void testSetGasPriceSuccess() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(Bytes.concatenate(SET_GASPRICE_SIG, UInt256.valueOf(100)), frame);
    assertThat(r.output()).isEqualTo(TRUE);
    verify(precompileAccount).setStorageValue(GASPRICE_SLOT, UInt256.valueOf(100));
  }
  @Test void testSetGasPriceShortCalldata() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(Bytes.concatenate(SET_GASPRICE_SIG, Bytes.of(0x01)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- enable/disable/status ----
  @Test void testEnableSuccess() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(ENABLE_SIG, frame);
    assertThat(r.output()).isEqualTo(TRUE);
    verify(precompileAccount).setStorageValue(STATUS_SLOT, UInt256.ONE);
  }
  @Test void testDisableSuccess() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(DISABLE_SIG, frame);
    assertThat(r.output()).isEqualTo(TRUE);
    verify(precompileAccount).setStorageValue(STATUS_SLOT, UInt256.ZERO);
  }

  // ---- gasPrice read ----
  @Test void testGasPriceReturnsStored() {
    when(precompileAccount.getStorageValue(GASPRICE_SLOT)).thenReturn(UInt256.valueOf(42));
    var r = contract.computePrecompile(GASPRICE_SIG, frame);
    assertThat(r.output()).isEqualTo(UInt256.valueOf(42));
  }

  // ---- gas requirements ----
  @Test void testGasReadCost() {
    assertThat(contract.gasRequirement(OWNER_SIG)).isEqualTo(1000L);
    assertThat(contract.gasRequirement(GASPRICE_SIG)).isEqualTo(1000L);
    assertThat(contract.gasRequirement(STATUS_SIG)).isEqualTo(1000L);
  }
  @Test void testGasWriteCost() {
    assertThat(contract.gasRequirement(Bytes.concatenate(SET_GASPRICE_SIG, UInt256.ONE))).isEqualTo(2000L);
    assertThat(contract.gasRequirement(Bytes.concatenate(ENABLE_SIG, UInt256.ONE))).isEqualTo(2000L);
  }

  // ---- static call ----
  @Test void testWriteBlockedInStaticCall() {
    when(frame.isStatic()).thenReturn(true);
    var r = contract.computePrecompile(Bytes.concatenate(INIT_OWNER_SIG, pad(SENDER)), frame);
    assertThat(r.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  // ---- unknown selector ----
  @Test void testUnknownSelector() {
    var r = contract.computePrecompile(Bytes.fromHexString("0xdeadbeef"), frame);
    assertThat(r.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }
}
