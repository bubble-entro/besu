/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evm.precompile;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Optional;
import javax.annotation.Nonnull;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GasFeeGrantPrecompiledContract extends AbstractPrecompiledContract {
  private static final Logger LOG = LoggerFactory.getLogger(GasFeeGrantPrecompiledContract.class);

  /** Generate from "feegrant.flag", See https://eips.ethereum.org/EIPS/eip-7201 */
  private static final UInt256 FEE_GRANT_FLAG_STORAGE =
      UInt256.fromHexString("0x330bb6449068d17e3815a045685a05a106741a6e960986b3c72eb86cb692da00");

  /** Ownable */
  private static final Bytes OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("owner()".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes INITIALIZED_SIGNATURE =
      Hash.keccak256(Bytes.of("initialized()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes INITIALIZE_OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("initializeOwner(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes TRANSFER_OWNERSHIP_SIGNATURE =
      Hash.keccak256(Bytes.of("transferOwnership(address)".getBytes(UTF_8))).slice(0, 4);

  /** GasFeeGrant */
  private static final Bytes GRANT_SIGNATURE =
      Hash.keccak256(Bytes.of("grant(address,address)".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes PERIOD_CAN_SPEND_SIGNATURE =
      Hash.keccak256(Bytes.of("periodCanSpend(address,address)".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes PERIOD_RESET_SIGNATURE =
      Hash.keccak256(Bytes.of("periodReset(address,address)".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes IS_EXPIRED_SIGNATURE =
      Hash.keccak256(Bytes.of("isExpired(address,address)".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes IS_GRANT_FOR_PROGRAM_SIGNATURE =
      Hash.keccak256(Bytes.of("isGrantedForProgram(address,address)".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes IS_GRANT_FOR_ALL_PROGRAM_SIGNATURE =
      Hash.keccak256(Bytes.of("isGrantedForAllProgram(address)".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes SET_FEE_GRANT_SIGNATURE =
      Hash.keccak256(
              Bytes.of(
                  "setFeeGrant(address,address,address,uint256,uint32,uint256,uint256)"
                      .getBytes(UTF_8)))
          .slice(0, 4);

  private static final Bytes REVOKE_FEE_GRANT_SIGNATURE =
      Hash.keccak256(Bytes.of("revokeFeeGrant(address,address)".getBytes(UTF_8))).slice(0, 4);

  /** Storage Layout */
  private static final UInt256 INIT_SLOT = UInt256.ZERO;

  private static final UInt256 OWNER_SLOT = UInt256.ONE;

  /** mapping(address => mapping(address => Grant)) */
  private static final UInt256 GRANTS_SLOT = UInt256.valueOf(2L);

  /** mapping(address => uint256) */
  private static final UInt256 GRANTS_COUNTER = UInt256.valueOf(3L);

  /** Returns */
  private static final Bytes FALSE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000");

  private static final Bytes TRUE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001");

  public GasFeeGrantPrecompiledContract(final GasCalculator gasCalculator) {
    super("GasFeeGrantPrecompiledContract", gasCalculator);
  }

  private UInt256 storageSlotGrant(final Address grantee, final Address program) {
    final Bytes32 root = Hash.keccak256(Bytes.concatenate(GRANTS_SLOT, grantee.getBytes()));
    final Bytes32 slot = Hash.keccak256(Bytes.concatenate(root, program.getBytes()));
    return UInt256.fromBytes(slot);
  }

  /** Modifier */
  private Bytes onlyOwner(final MutableAccount contract, final Address senderAddress) {
    final Address storedOwner = Address.wrap(contract.getStorageValue(OWNER_SLOT).slice(12, 20));
    if (storedOwner.equals(senderAddress)) {
      return TRUE;
    } else {
      return FALSE;
    }
  }

  private Bytes owner(final MutableAccount contract) {
    return contract.getStorageValue(OWNER_SLOT);
  }

  private Bytes initialized(final MutableAccount contract) {
    return contract.getStorageValue(INIT_SLOT);
  }

  private Bytes initializeOwner(final MutableAccount contract, final Bytes calldata) {
    if (initialized(contract).equals(TRUE)) {
      return FALSE;
    } else {
      final UInt256 initialOwner = UInt256.fromBytes(calldata);
      if (initialOwner.isZero()) {
        return FALSE;
      }
      if (contract.getNonce() == 0L) {
        contract.incrementNonce();
      }
      contract.setStorageValue(OWNER_SLOT, initialOwner);
      contract.setStorageValue(INIT_SLOT, UInt256.ONE);
      return TRUE;
    }
  }

  private Bytes transferOwnership(
      final MutableAccount contract, final Address senderAddress, final Bytes calldata) {
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      final UInt256 newOwner = UInt256.fromBytes(calldata);
      if (newOwner.isZero()) {
        return FALSE;
      }
      contract.setStorageValue(OWNER_SLOT, newOwner);
      return TRUE;
    }
  }

  private Bytes isGrantedForProgram(final MutableAccount contract, final Bytes calldata) {
    if (calldata.size() < 64) {
      return FALSE;
    }
    final Address granteeAddress = Address.wrap(calldata.slice(12, 20));
    final Address programAddress = Address.wrap(calldata.slice(44, 20));
    final UInt256 rootSlot = storageSlotGrant(granteeAddress, programAddress);
    if (contract.getStorageValue(rootSlot.add(1L)).isZero()) {
      return FALSE;
    } else {
      return TRUE;
    }
  }

  private Bytes isGrantedForAllProgram(final MutableAccount contract, final Bytes calldata) {
    if (calldata.size() < 32) {
      return FALSE;
    }
    final Address granteeAddress = Address.wrap(calldata.slice(12, 20));
    final UInt256 rootSlot = storageSlotGrant(granteeAddress, Address.ZERO);
    if (contract.getStorageValue(rootSlot.add(1L)).isZero()) {
      return FALSE;
    } else {
      return TRUE;
    }
  }

  private Bytes grant(
      final MutableAccount contract, final Bytes calldata, final UInt256 blockNumber) {
    if (calldata.size() < 64) {
      return FALSE;
    }
    final Address granteeAddress = Address.wrap(calldata.slice(12, 20));
    final Address programAddress = Address.wrap(calldata.slice(44, 20));
    final UInt256 rootSlot = storageSlotGrant(granteeAddress, programAddress);
    final UInt256 granter = contract.getStorageValue(rootSlot);
    final UInt256 allowance = contract.getStorageValue(rootSlot.add(1L));
    final UInt256 spendLimit = contract.getStorageValue(rootSlot.add(2L));
    final UInt256 periodLimit = contract.getStorageValue(rootSlot.add(3L));
    final UInt256 periodCanSpend =
        UInt256.fromBytes(periodCanSpend(contract, calldata, blockNumber));
    final UInt256 startTime = contract.getStorageValue(rootSlot.add(5L));
    final UInt256 endTime = contract.getStorageValue(rootSlot.add(6L));
    final UInt256 latestTransaction = contract.getStorageValue(rootSlot.add(7L));
    final UInt256 period = contract.getStorageValue(rootSlot.add(8L));
    return Bytes.concatenate(
        granter,
        allowance,
        spendLimit,
        periodLimit,
        periodCanSpend,
        startTime,
        endTime,
        latestTransaction,
        period);
  }

  private Bytes setFeeGrant(
      final MutableAccount contract,
      final Address senderAddress,
      final WorldUpdater worldUpdater,
      final Bytes calldata,
      final UInt256 blockNumber) {
    if (calldata.size() < 224) {
      return FALSE;
    }
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      if (isGrantedForProgram(contract, calldata.slice(32)).isZero()) {
        final Address granterAddress = Address.wrap(calldata.slice(12, 20));
        final Address granteeAddress = Address.wrap(calldata.slice(44, 20));
        final Address programAddress = Address.wrap(calldata.slice(76, 20));
        final UInt256 rootSlot = storageSlotGrant(granteeAddress, programAddress);
        final UInt256 spendLimit = UInt256.fromBytes(calldata.slice(96, 32));
        final UInt256 period = UInt256.fromBytes(calldata.slice(128, 32));
        final UInt256 periodLimit = UInt256.fromBytes(calldata.slice(160, 32));
        final UInt256 endTime = UInt256.fromBytes(calldata.slice(192));
        UInt256 allowance = UInt256.ONE;
        if (granterAddress.equals(Address.ZERO)) {
          return FALSE;
        }
        if (spendLimit.isZero()
            || granterAddress.equals(Address.ZERO)
            || granteeAddress.equals(Address.ZERO)) {
          return FALSE;
        }
        if (!period.isZero() && !periodLimit.isZero()) {
          if (spendLimit.compareTo(periodLimit) > 0) {
            return FALSE;
          }
          allowance = UInt256.valueOf(2L);
        }
        final MutableAccount grantee = worldUpdater.getOrCreate(granteeAddress);
        // if account is empty create it by increment nonce.
        if (grantee.getNonce() == 0L) {
          grantee.incrementNonce();
        }
        grantee.setStorageValue(FEE_GRANT_FLAG_STORAGE, UInt256.ONE);
        contract.setStorageValue(rootSlot, UInt256.fromBytes(Bytes32.leftPad(granterAddress.getBytes())));
        contract.setStorageValue(rootSlot.add(1L), allowance);
        contract.setStorageValue(rootSlot.add(2L), spendLimit);
        contract.setStorageValue(rootSlot.add(3L), periodLimit);
        contract.setStorageValue(rootSlot.add(4L), periodLimit);
        contract.setStorageValue(rootSlot.add(5L), blockNumber);
        contract.setStorageValue(rootSlot.add(6L), endTime);
        contract.setStorageValue(rootSlot.add(7L), blockNumber);
        contract.setStorageValue(rootSlot.add(8L), period);
        final UInt256 grantCounterSlot =
            UInt256.fromBytes(Hash.keccak256(Bytes.concatenate(GRANTS_COUNTER, granteeAddress.getBytes())));
        UInt256 counter = contract.getStorageValue(grantCounterSlot).add(UInt256.ONE);
        contract.setStorageValue(grantCounterSlot, counter);
        return TRUE;
      } else {
        return FALSE;
      }
    }
  }

  @SuppressWarnings("UnusedVariable")
  private Bytes revokeFeeGrant(
      final MutableAccount contract,
      final Address senderAddress,
      final WorldUpdater worldUpdater,
      final Bytes calldata) {
    if (calldata == null || calldata.size() < 64) {
      return FALSE;
    }
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      final Address granteeAddress = Address.wrap(calldata.slice(12, 20));
      if (granteeAddress.equals(Address.ZERO)) {
        return FALSE;
      }
      final Address programAddress = Address.wrap(calldata.slice(44, 20));
      final UInt256 rootSlot = storageSlotGrant(granteeAddress, programAddress);
      contract.setStorageValue(rootSlot, UInt256.ZERO);
      contract.setStorageValue(rootSlot.add(1L), UInt256.ZERO);
      contract.setStorageValue(rootSlot.add(2L), UInt256.ZERO);
      contract.setStorageValue(rootSlot.add(3L), UInt256.ZERO);
      contract.setStorageValue(rootSlot.add(4L), UInt256.ZERO);
      contract.setStorageValue(rootSlot.add(5L), UInt256.ZERO);
      contract.setStorageValue(rootSlot.add(6L), UInt256.ZERO);
      contract.setStorageValue(rootSlot.add(7L), UInt256.ZERO);
      contract.setStorageValue(rootSlot.add(8L), UInt256.ZERO);
      final UInt256 grantCounterSlot =
          UInt256.fromBytes(Hash.keccak256(Bytes.concatenate(GRANTS_COUNTER, granteeAddress.getBytes())));
      final UInt256 counter = contract.getStorageValue(grantCounterSlot).subtract(UInt256.ONE);
      contract.setStorageValue(grantCounterSlot, counter);
      if (counter.isZero()) {
        final MutableAccount grantee = worldUpdater.getOrCreate(granteeAddress);
        grantee.setStorageValue(FEE_GRANT_FLAG_STORAGE, UInt256.ZERO);
      }
      return TRUE;
    }
  }

  @SuppressWarnings("UnusedVariable")
  private Bytes periodCanSpend(
      final MutableAccount contract, final Bytes calldata, final UInt256 blockNumber) {
    if (calldata.size() < 64) {
      return FALSE;
    }
    final Address granteeAddress = Address.wrap(calldata.slice(12, 20));
    Address programAddress = Address.wrap(calldata.slice(44, 20));
    UInt256 rootSlot = storageSlotGrant(granteeAddress, Address.ZERO);
    if (contract.getStorageValue(rootSlot.add(1L)).equals(UInt256.valueOf(2L))) {
      programAddress = Address.ZERO;
    }
    rootSlot = storageSlotGrant(granteeAddress, programAddress);
    if (contract.getStorageValue(rootSlot.add(1L)).equals(UInt256.valueOf(2L))) {
      final UInt256 latestTransaction = contract.getStorageValue(rootSlot.add(7L));
      final UInt256 period = contract.getStorageValue(rootSlot.add(8L));
      final UInt256 periodReset = UInt256.fromBytes(periodReset(contract, calldata, blockNumber));
      if (latestTransaction.add(period).compareTo(periodReset) < 0) {
        return contract.getStorageValue(rootSlot.add(3L));
      } else {
        return contract.getStorageValue(rootSlot.add(4L));
      }
    } else {
      return FALSE;
    }
  }

  private Bytes periodReset(
      final MutableAccount contract, final Bytes calldata, final UInt256 blockNumber) {
    if (calldata.size() < 64) {
      return FALSE;
    }
    final Address granteeAddress = Address.wrap(calldata.slice(12, 20));
    Address programAddress = Address.wrap(calldata.slice(44, 20));
    UInt256 rootSlot = storageSlotGrant(granteeAddress, Address.ZERO);
    if (contract.getStorageValue(rootSlot.add(1L)).equals(UInt256.valueOf(2L))) {
      programAddress = Address.ZERO;
    }
    rootSlot = storageSlotGrant(granteeAddress, programAddress);
    if (contract.getStorageValue(rootSlot.add(1L)).equals(UInt256.valueOf(2L))) {
      UInt256 resetBlock = contract.getStorageValue(rootSlot.add(5L));
      final UInt256 period = contract.getStorageValue(rootSlot.add(8L));
      if (period.isZero()) {
        return resetBlock;
      }
      if (blockNumber.compareTo(resetBlock) < 0) { 
        return resetBlock; 
      }
      final UInt256 cycles = (blockNumber.subtract(resetBlock)).divide(period);
      if (!cycles.isZero()) {
        resetBlock = resetBlock.add(cycles.multiply(period));
      }
      return resetBlock;
    }
    return FALSE;
  }

  private Bytes isExpired(
      final MutableAccount contract, final Bytes calldata, final UInt256 blockNumber) {
    final Address granteeAddress = Address.wrap(calldata.slice(12, 20));
    final Address programAddress = Address.wrap(calldata.slice(44, 20));
    final UInt256 rootSlot = storageSlotGrant(granteeAddress, programAddress);
    final UInt256 endTime = contract.getStorageValue(rootSlot.add(6L));
    LOG.debug("Gas fee grant of {} for program {} expired at block {}", granteeAddress, programAddress, endTime);
    if (endTime.isZero()) {
      return FALSE;
    } else {
      final Bytes result = blockNumber.compareTo(endTime) >= 0 ? TRUE : FALSE;
      LOG.debug("Is fee grant expired {}", result);
      return result;
    }
  }

  @Override
  public long gasRequirement(final Bytes input) {
    final Bytes function = input.slice(0, 4);
    if (function.equals(INITIALIZE_OWNER_SIGNATURE)
        || function.equals(TRANSFER_OWNERSHIP_SIGNATURE)
        || function.equals(SET_FEE_GRANT_SIGNATURE)
        || function.equals(REVOKE_FEE_GRANT_SIGNATURE)) {
      // gas cost for wite operation.
      return 2000;
    } else {
      // gas cost for write operation.
      return 1000;
    }
  }

  @Nonnull
  @Override
  public PrecompileContractResult computePrecompile(
      final Bytes input, @Nonnull final MessageFrame messageFrame) {
    if (input.isEmpty()) {
      return PrecompileContractResult.halt(
          null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
    } else {
      final boolean isStaticCall = messageFrame.isStatic();
      final Bytes function = input.slice(0, 4);
      final Bytes calldata = input.slice(4);
      final WorldUpdater worldUpdater = messageFrame.getWorldUpdater();
      final Address senderAddress = messageFrame.getSenderAddress();
      final MutableAccount precompile = worldUpdater.getOrCreate(Address.GASFEE_GRANT);
      final UInt256 blockNumber = UInt256.valueOf(messageFrame.getBlockValues().getNumber());
      if (function.equals(OWNER_SIGNATURE)) {
        return PrecompileContractResult.success(owner(precompile));
      } else if (function.equals(INITIALIZED_SIGNATURE)) {
        return PrecompileContractResult.success(initialized(precompile));
      } else if (function.equals(INITIALIZE_OWNER_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(initializeOwner(precompile, calldata));
      } else if (function.equals(TRANSFER_OWNERSHIP_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(
            transferOwnership(precompile, senderAddress, calldata));
      } else if (function.equals(GRANT_SIGNATURE)) {
        return PrecompileContractResult.success(grant(precompile, calldata, blockNumber));
      } else if (function.equals(PERIOD_CAN_SPEND_SIGNATURE)) {
        return PrecompileContractResult.success(periodCanSpend(precompile, calldata, blockNumber));
      } else if (function.equals(PERIOD_RESET_SIGNATURE)) {
        return PrecompileContractResult.success(periodReset(precompile, calldata, blockNumber));
      } else if (function.equals(IS_EXPIRED_SIGNATURE)) {
        if (isGrantedForProgram(precompile, calldata).isZero()) {
          return PrecompileContractResult.success(TRUE);
        } else {
          return PrecompileContractResult.success(isExpired(precompile, calldata, blockNumber));
        }
      } else if (function.equals(IS_GRANT_FOR_PROGRAM_SIGNATURE)) {
        return PrecompileContractResult.success(isGrantedForProgram(precompile, calldata));
      } else if (function.equals(IS_GRANT_FOR_ALL_PROGRAM_SIGNATURE)) {
        return PrecompileContractResult.success(isGrantedForAllProgram(precompile, calldata));
      } else if (function.equals(SET_FEE_GRANT_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(
            setFeeGrant(precompile, senderAddress, worldUpdater, calldata, blockNumber));
      } else if (function.equals(REVOKE_FEE_GRANT_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(
            revokeFeeGrant(precompile, senderAddress, worldUpdater, calldata));
      } else {
        LOG.debug("Failed function {} not found", function);
        return PrecompileContractResult.halt(
            null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
      }
    }
  }
}
