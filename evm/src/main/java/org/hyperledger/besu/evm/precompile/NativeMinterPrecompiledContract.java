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
import org.hyperledger.besu.datatypes.Wei;
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

public class NativeMinterPrecompiledContract extends AbstractPrecompiledContract {
  private static final Logger LOG = LoggerFactory.getLogger(NativeMinterPrecompiledContract.class);

  /** Ownable */
  private static final Bytes OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("owner()".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes INITIALIZED_SIGNATURE =
      Hash.keccak256(Bytes.of("initialized()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes INITIALIZE_OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("initializeOwner(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes TRANSFER_OWNERSHIP_SIGNATURE =
      Hash.keccak256(Bytes.of("transferOwnership(address)".getBytes(UTF_8))).slice(0, 4);

  /** NativeMinter */
  private static final Bytes MINT_SIGNATURE =
      Hash.keccak256(Bytes.of("mint(address,uint256)".getBytes(UTF_8))).slice(0, 4);

  /** Storage Layout */
  private static final UInt256 INIT_SLOT = UInt256.ZERO;

  private static final UInt256 OWNER_SLOT = UInt256.ONE;

  /** Returns */
  private static final Bytes FALSE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000");

  private static final Bytes TRUE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001");

  public NativeMinterPrecompiledContract(final GasCalculator gasCalculator) {
    super("NativeMinterPrecompiledContract", gasCalculator);
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

  private Bytes mint(
      final MutableAccount contract,
      final WorldUpdater worldUpdater,
      final Address senderAddress,
      final Bytes calldata) {
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      final Address recipientAddress = Address.wrap(calldata.slice(12, 20));
      final UInt256 value = UInt256.fromBytes(calldata.slice(32));
      if (value.isZero() || recipientAddress.equals(Address.ZERO)) {
        return FALSE;
      }
      final MutableAccount recipientAccount = worldUpdater.getOrCreate(recipientAddress);
      recipientAccount.incrementBalance(Wei.of(value));
      return TRUE;
    }
  }

  @Override
  public long gasRequirement(final Bytes input) {
    final Bytes function = input.slice(0, 4);
    if (function.equals(OWNER_SIGNATURE) || function.equals(INITIALIZED_SIGNATURE)) {
      return 1000;
    } else {
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
      final MutableAccount precompile = worldUpdater.getOrCreate(Address.NATIVE_MINTER);
      if (function.equals(OWNER_SIGNATURE)) {
        return PrecompileContractResult.success(owner(precompile));
      } else if (function.equals(INITIALIZED_SIGNATURE)) {
        return PrecompileContractResult.success(initialized(precompile));
      } else if (function.equals(INITIALIZE_OWNER_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(initializeOwner(precompile, calldata));
      } else if (function.equals(TRANSFER_OWNERSHIP_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(
            transferOwnership(precompile, senderAddress, calldata));
      } else if (function.equals(MINT_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(
            mint(precompile, worldUpdater, senderAddress, calldata));
      } else {
        LOG.debug("Failed function {} not found", function);
        return PrecompileContractResult.halt(
            null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
      }
    }
  }
}
