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
import org.apache.tuweni.units.bigints.UInt256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GasPricePrecompiledContract extends AbstractPrecompiledContract {
  private static final Logger LOG = LoggerFactory.getLogger(GasPricePrecompiledContract.class);

  /** Ownable */
  private static final Bytes OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("owner()".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes INITIALIZED_SIGNATURE =
      Hash.keccak256(Bytes.of("initialized()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes INITIALIZE_OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("initializeOwner(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes TRANSFER_OWNERSHIP_SIGNATURE =
      Hash.keccak256(Bytes.of("transferOwnership(address)".getBytes(UTF_8))).slice(0, 4);

  /** Status */
  private static final Bytes ENABLE_SIGNATURE =
      Hash.keccak256(Bytes.of("enable()".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes DISABLE_SIGNATURE =
      Hash.keccak256(Bytes.of("disable()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes STATUS_SIGNATURE =
      Hash.keccak256(Bytes.of("status()".getBytes(UTF_8))).slice(0, 4);

  /** GasPrice */
  private static final Bytes GASPRICE_SIGNATURE =
      Hash.keccak256(Bytes.of("gasPrice()".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes SET_GASPRICE_SIGNATURE =
      Hash.keccak256(Bytes.of("setGasPrice(uint256)".getBytes(UTF_8))).slice(0, 4);

  /** Storage Layout */
  private static final UInt256 INIT_SLOT = UInt256.ZERO;

  private static final UInt256 OWNER_SLOT = UInt256.ONE;

  private static final UInt256 STATUS_SLOT = UInt256.valueOf(2L);

  private static final UInt256 GASPRICE_SLOT = UInt256.valueOf(3L);

  /** Returns */
  private static final Bytes FALSE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000");

  private static final Bytes TRUE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001");

  public GasPricePrecompiledContract(final GasCalculator gasCalculator) {
    super("GasPricePrecompiledContract", gasCalculator);
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

  private Bytes enable(final MutableAccount contract, final Address senderAddress) {
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      contract.setStorageValue(STATUS_SLOT, UInt256.ONE);
      return TRUE;
    }
  }

  private Bytes disable(final MutableAccount contract, final Address senderAddress) {
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      contract.setStorageValue(STATUS_SLOT, UInt256.ZERO);
      return TRUE;
    }
  }

  private Bytes status(final MutableAccount contract) {
    return contract.getStorageValue(STATUS_SLOT);
  }

  private Bytes gasPrice(final MutableAccount contract) {
    return contract.getStorageValue(GASPRICE_SLOT);
  }

  private Bytes setGasPrice(
      final MutableAccount contract, final Address senderAddress, final Bytes calldata) {
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      final UInt256 newGasPrice = UInt256.fromBytes(calldata);
      contract.setStorageValue(GASPRICE_SLOT, newGasPrice);
      return TRUE;
    }
  }

  @Override
  public long gasRequirement(final Bytes input) {
    final Bytes function = input.slice(0, 4);
    if (function.equals(OWNER_SIGNATURE)
        || function.equals(INITIALIZED_SIGNATURE)
        || function.equals(GASPRICE_SIGNATURE)
        || function.equals(STATUS_SIGNATURE)) {
      // gas cost for read operation.
      return 1000;
    } else {
      // gas cost for write operation.
      return 2000;
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
      final MutableAccount precompile = worldUpdater.getOrCreate(Address.GASPRICE);
      if (function.equals(OWNER_SIGNATURE)) {
        return PrecompileContractResult.success(owner(precompile));
      } else if (function.equals(INITIALIZED_SIGNATURE)) {
        return PrecompileContractResult.success(initialized(precompile));
      } else if (function.equals(INITIALIZE_OWNER_SIGNATURE)  && !isStaticCall) {
        return PrecompileContractResult.success(initializeOwner(precompile, calldata));
      } else if (function.equals(TRANSFER_OWNERSHIP_SIGNATURE)  && !isStaticCall) {
        return PrecompileContractResult.success(
            transferOwnership(precompile, senderAddress, calldata));
      } else if (function.equals(GASPRICE_SIGNATURE)) {
        return PrecompileContractResult.success(gasPrice(precompile));
      } else if (function.equals(STATUS_SIGNATURE)) {
        return PrecompileContractResult.success(status(precompile));
      } else if (function.equals(ENABLE_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(enable(precompile, senderAddress));
      } else if (function.equals(DISABLE_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(disable(precompile, senderAddress));
      } else if (function.equals(SET_GASPRICE_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(setGasPrice(precompile, senderAddress, calldata));
      } else {
        LOG.debug("Failed function {} not found", function);
        return PrecompileContractResult.halt(
            null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
      }
    }
  }
}
