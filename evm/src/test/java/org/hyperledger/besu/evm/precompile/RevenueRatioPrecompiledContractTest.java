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

public class RevenueRatioPrecompiledContractTest {

  private RevenueRatioPrecompiledContract contract;
  private MessageFrame frame;
  private WorldUpdater worldUpdater;
  private MutableAccount precompileAccount;

  private static final Bytes INIT_OWNER_SIG =
      Hash.keccak256(Bytes.of("initializeOwner(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes TRANSFER_SIG =
      Hash.keccak256(Bytes.of("transferOwnership(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes ENABLE_SIG =
      Hash.keccak256(Bytes.of("enable()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes SENDER_RATIO_SIG =
      Hash.keccak256(Bytes.of("senderRatio()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes COINBASE_RATIO_SIG =
      Hash.keccak256(Bytes.of("coinbaseRatio()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes SET_RATIO_SIG =
      Hash.keccak256(Bytes.of("setRevenueRatio(uint8,uint8,uint8,uint8)".getBytes(UTF_8))).slice(0, 4);

  private static final UInt256 OWNER_SLOT = UInt256.ONE;
  private static final UInt256 SENDER_RATIO_SLOT = UInt256.valueOf(3L);
  private static final UInt256 COINBASE_RATIO_SLOT = UInt256.valueOf(4L);
  private static final UInt256 PROVIDER_RATIO_SLOT = UInt256.valueOf(5L);
  private static final UInt256 TREASURY_RATIO_SLOT = UInt256.valueOf(6L);

  private static final Bytes FALSE = Bytes.fromHexString(
      "0x0000000000000000000000000000000000000000000000000000000000000000");
  private static final Bytes TRUE = Bytes.fromHexString(
      "0x0000000000000000000000000000000000000000000000000000000000000001");

  private static final Address SENDER =
      Address.fromHexString("0x1111111111111111111111111111111111111111");

  private static Bytes32 pad(final Address a) { return Bytes32.leftPad(a.getBytes()); }

  @BeforeEach
  void setup() {
    contract = new RevenueRatioPrecompiledContract(new FrontierGasCalculator());
    frame = Mockito.mock(MessageFrame.class);
    worldUpdater = Mockito.mock(WorldUpdater.class);
    precompileAccount = Mockito.mock(MutableAccount.class);
    when(frame.getWorldUpdater()).thenReturn(worldUpdater);
    when(frame.getSenderAddress()).thenReturn(SENDER);
    when(frame.isStatic()).thenReturn(false);
    when(worldUpdater.getOrCreate(Address.REVENUE_RATIO)).thenReturn(precompileAccount);
    when(precompileAccount.getStorageValue(any(UInt256.class))).thenReturn(UInt256.ZERO);
    when(precompileAccount.getNonce()).thenReturn(0L);
  }

  @Test void testEmptyInputHalts() {
    var r = contract.computePrecompile(Bytes.EMPTY, frame);
    assertThat(r.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }

  @Test void testInitOwnerSuccess() {
    var r = contract.computePrecompile(Bytes.concatenate(INIT_OWNER_SIG, pad(SENDER)), frame);
    assertThat(r.output()).isEqualTo(TRUE);
  }
  @Test void testInitOwnerShortCalldata() {
    var r = contract.computePrecompile(Bytes.concatenate(INIT_OWNER_SIG, Bytes.of(0x01)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }
  @Test void testTransferShortCalldata() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(Bytes.concatenate(TRANSFER_SIG, Bytes.of(0x01)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- setRevenueRatio ----
  @Test void testSetRevenueRatioSuccess() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    // 10 sender + 40 coinbase + 30 provider + 20 treasury = 100
    Bytes calldata = Bytes.concatenate(
        SET_RATIO_SIG,
        UInt256.valueOf(10), UInt256.valueOf(40), UInt256.valueOf(30), UInt256.valueOf(20));
    var r = contract.computePrecompile(calldata, frame);
    assertThat(r.output()).isEqualTo(TRUE);
    verify(precompileAccount).setStorageValue(SENDER_RATIO_SLOT, UInt256.valueOf(10));
    verify(precompileAccount).setStorageValue(COINBASE_RATIO_SLOT, UInt256.valueOf(40));
    verify(precompileAccount).setStorageValue(PROVIDER_RATIO_SLOT, UInt256.valueOf(30));
    verify(precompileAccount).setStorageValue(TREASURY_RATIO_SLOT, UInt256.valueOf(20));
  }
  @Test void testSetRevenueRatioNotSumTo100() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    Bytes calldata = Bytes.concatenate(
        SET_RATIO_SIG,
        UInt256.valueOf(10), UInt256.valueOf(10), UInt256.valueOf(10), UInt256.valueOf(10));
    var r = contract.computePrecompile(calldata, frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }
  @Test void testSetRevenueRatioShortCalldata() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(Bytes.concatenate(SET_RATIO_SIG, UInt256.valueOf(10)), frame);
    assertThat(r.output()).isEqualTo(FALSE);
  }

  // ---- senderRatio read (V2 specific) ----
  @Test void testSenderRatioReturnsStored() {
    when(precompileAccount.getStorageValue(SENDER_RATIO_SLOT)).thenReturn(UInt256.valueOf(15));
    var r = contract.computePrecompile(SENDER_RATIO_SIG, frame);
    assertThat(r.output()).isEqualTo(UInt256.valueOf(15));
  }

  // ---- enable/disable ----
  @Test void testEnableSuccess() {
    when(precompileAccount.getStorageValue(OWNER_SLOT)).thenReturn(UInt256.fromBytes(pad(SENDER)));
    var r = contract.computePrecompile(ENABLE_SIG, frame);
    assertThat(r.output()).isEqualTo(TRUE);
  }

  // ---- gas costs ----
  @Test void testGasReadCost() {
    assertThat(contract.gasRequirement(SENDER_RATIO_SIG)).isEqualTo(1000L);
    assertThat(contract.gasRequirement(COINBASE_RATIO_SIG)).isEqualTo(1000L);
  }
  @Test void testGasWriteCost() {
    assertThat(contract.gasRequirement(Bytes.concatenate(SET_RATIO_SIG, UInt256.ONE))).isEqualTo(2000L);
  }

  @Test void testUnknownSelector() {
    var r = contract.computePrecompile(Bytes.fromHexString("0xdeadbeef"), frame);
    assertThat(r.getHaltReason()).isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }
}
