package org.hyperledger.besu.evm.precompile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

public class AddressRegistryPrecompiledContractTest {

  private AddressRegistryPrecompiledContract contract;
  private MessageFrame frame;
  private WorldUpdater worldUpdater;
  private MutableAccount precompileAccount;

  private static final Bytes INIT_OWNER_SIG =
      Hash.keccak256(Bytes.of("initializeOwner(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes CONTAINS_SIG =
      Hash.keccak256(Bytes.of("contains(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes DISCOVERY_SIG =
      Hash.keccak256(Bytes.of("discovery(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes ADD_SIG =
      Hash.keccak256(Bytes.of("addToRegistry(address,address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes REMOVE_SIG =
      Hash.keccak256(Bytes.of("removeFromRegistry(address)".getBytes(UTF_8))).slice(0, 4);

  private static final UInt256 OWNER_SLOT = UInt256.ONE;
  private static final UInt256 REGISTRY_SLOT = UInt256.valueOf(2L);

  private static final Bytes FALSE = Bytes.fromHexString(
      "0x0000000000000000000000000000000000000000000000000000000000000000");
  private static final Bytes TRUE = Bytes.fromHexString(
      "0x0000000000000000000000000000000000000000000000000000000000000001");

  private static final Address SENDER =
      Address.fromHexString("0x1111111111111111111111111111111111111111");
  private static final Address TARGET =
      Address.fromHexString("0x3333333333333333333333333333333333333333");
  private static final Address INITIATOR =
      Address.fromHexString("0x4444444444444444444444444444444444444444");

  private static Bytes32 pad(final Address a) { return Bytes32.leftPad(a.getBytes()); }

  /** V2 EVM-compatible slot calculation (must match contract) */
  private UInt256 expectedSlot(final Address addr) {
    return UInt256.fromBytes(
        Hash.keccak256(Bytes.concatenate(Bytes32.wrap(REGISTRY_SLOT), Bytes32.leftPad(addr.getBytes()))));
  }

  @BeforeEach
  void setup() {
    contract = new AddressRegistryPrecompiledContract(new FrontierGasCalculator());
    frame = Mockito.mock(MessageFrame.class);
    worldUpdater = Mockito.mock(WorldUpdater.class);
    precompileAccount = Mockito.mock(MutableAccount.class);
    when(frame.getWorldUpdater()).thenReturn(worldUpdater);
    when(frame.getSenderAddress()).thenReturn(SENDER);
    when(frame.isStatic()).thenReturn(false);
    when(worldUpdater.getOrCreate(Address.ADDRESS_REGISTRY)).thenReturn(precompileAccount);
    when(precompileAccount.getStorageValue(any(UInt256.class))).thenReturn(UInt256.ZERO);
    when(precompileAccount.getNonce()).thenReturn(0L);
  }

  @Test void testEmptyInputHalts() {
    var r = contract.computePrecompile(Bytes.EMPTY, frame);
    assertThat(r.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  // ---- initializeOwner ----
  @Test void testInitOwnerSuccess() {
    var r = contract.computePrecompile(Bytes.concatenate(INIT_OWNER_SIG, pad(SENDER)), frame);
    assertThat(r.output()).isEqualTo(TRUE);
  }
  @Test void testInitOwnerShortCalldata() {
    var r = contract.computePrecompile(Bytes.concatenate(INIT_OWNER_SIG, Bytes.of(0x01)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- contains ----
  @Test void testContainsNotRegistered() {
    var r = contract.computePrecompile(Bytes.concatenate(CONTAINS_SIG, pad(TARGET)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }
  @Test void testContainsRegistered() {
    UInt256 slot = expectedSlot(TARGET);
    when(precompileAccount.getStorageValue(slot)).thenReturn(UInt256.ONE);
    var r = contract.computePrecompile(Bytes.concatenate(CONTAINS_SIG, pad(TARGET)), frame);
    assertThat(r.output()).isEqualTo(TRUE);
  }
  @Test void testContainsShortCalldata() {
    var r = contract.computePrecompile(Bytes.concatenate(CONTAINS_SIG, Bytes.of(0x01)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- discovery ----
  @Test void testDiscoveryShortCalldata() {
    var r = contract.computePrecompile(Bytes.concatenate(DISCOVERY_SIG, Bytes.of(0x01)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- addToRegistry ----
  @Test void testAddToRegistrySuccess() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    Bytes calldata = Bytes.concatenate(ADD_SIG, pad(TARGET), pad(INITIATOR));
    var r = contract.computePrecompile(calldata, frame);
    assertThat(r.output()).isEqualTo(TRUE);
  }
  @Test void testAddToRegistryShortCalldata() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(Bytes.concatenate(ADD_SIG, pad(TARGET)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }
  @Test void testAddToRegistryZeroAddress() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(Bytes.concatenate(ADD_SIG, pad(Address.ZERO), pad(INITIATOR)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- removeFromRegistry ----
  @Test void testRemoveShortCalldata() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(Bytes.concatenate(REMOVE_SIG, Bytes.of(0x01)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- gas costs ----
  @Test void testGasReadCost() {
    assertThat(contract.gasRequirement(CONTAINS_SIG)).isEqualTo(1000L);
    assertThat(contract.gasRequirement(DISCOVERY_SIG)).isEqualTo(1000L);
  }
  @Test void testGasWriteCost() {
    assertThat(contract.gasRequirement(Bytes.concatenate(ADD_SIG, pad(TARGET), pad(INITIATOR)))).isEqualTo(2000L);
  }

  @Test void testUnknownSelector() {
    var r = contract.computePrecompile(Bytes.fromHexString("0xdeadbeef"), frame);
    assertThat(r.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }
}
