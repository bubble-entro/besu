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

public class AddressRegistryPrecompiledContract extends AbstractPrecompiledContract {
  private static final Logger LOG =
      LoggerFactory.getLogger(AddressRegistryPrecompiledContract.class);

  /** Ownable */
  private static final Bytes OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("owner()".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes INITIALIZED_SIGNATURE =
      Hash.keccak256(Bytes.of("initialized()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes INITIALIZE_OWNER_SIGNATURE =
      Hash.keccak256(Bytes.of("initializeOwner(address)".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes TRANSFER_OWNERSHIP_SIGNATURE =
      Hash.keccak256(Bytes.of("transferOwnership(address)".getBytes(UTF_8))).slice(0, 4);

  /** AddressRegistry */
  private static final Bytes CONTAINS_SIGNATURE =
      Hash.keccak256(Bytes.of("contains(address)".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes DISCOVERY_SIGNATURE =
      Hash.keccak256(Bytes.of("discovery(address)".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes ADD_TO_REGISTRY_SIGNATURE =
      Hash.keccak256(Bytes.of("addToRegistry(address,address)".getBytes(UTF_8))).slice(0, 4);
  ;
  private static final Bytes REMOVE_FROM_REGISTRY_SIGNATURE =
      Hash.keccak256(Bytes.of("removeFromRegistry(address)".getBytes(UTF_8))).slice(0, 4);
  ;

  /** Storage Layout */
  private static final UInt256 INIT_SLOT = UInt256.ZERO;

  private static final UInt256 OWNER_SLOT = UInt256.ONE;

  private static final UInt256 REGISTRY_SLOT = UInt256.valueOf(2L);

  /** Returns */
  private static final Bytes FALSE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000");

  private static final Bytes TRUE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001");

  public AddressRegistryPrecompiledContract(final GasCalculator gasCalculator) {
    super("AddressRegistryPrecompiledContract", gasCalculator);
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

  // for calculate storage slot of mapping(address => bool)
  private UInt256 storageSlot(final Address address) {
    final Bytes slotKey = Bytes.concatenate(REGISTRY_SLOT, address.getBytes());
    return UInt256.fromBytes(Bytes32.leftPad(Hash.keccak256(slotKey)));
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

  private Bytes contains(final MutableAccount contract, final Bytes calldata) {
    if (calldata.size() < 32) {
      return FALSE;
    }
    final Address address = Address.wrap(calldata.slice(12, 20));
    final UInt256 slot = storageSlot(address);
    if (contract.getStorageValue(slot).isZero()) {
      return FALSE;
    } else {
      return TRUE;
    }
  }

  private Bytes discovery(final MutableAccount contract, final Bytes calldata) {
    if (calldata.size() < 32) {
      return FALSE;
    }
    final UInt256 slot = storageSlot(Address.wrap(calldata.slice(12, 20)));
    return contract.getStorageValue(slot);
  }

  private Bytes addToRegistry(
      final MutableAccount contract, final Address senderAddress, final Bytes calldata) {
    if (calldata.size() < 64) {
      return FALSE;
    }
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      final Address toAddAddress = Address.wrap(calldata.slice(12, 20));
      final Address initiatorAddress = Address.wrap(calldata.slice(44, 20));
      // preventing add zero address to register
      if (toAddAddress.equals(Address.ZERO) || initiatorAddress.equals(Address.ZERO)) {
        return FALSE;
      }
      final UInt256 slot = storageSlot(toAddAddress);
      contract.setStorageValue(slot, UInt256.fromBytes(calldata.slice(32)));
      return TRUE;
    }
  }

  private Bytes removeFromRegistry(
      final MutableAccount contract, final Address senderAddress, final Bytes calldata) {
    if (calldata.size() < 32) {
      return FALSE;
    }
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      final UInt256 slot = storageSlot(Address.wrap(calldata.slice(12, 20)));
      if (contract.getStorageValue(slot).isZero()) {
        return FALSE;
      }
      contract.setStorageValue(slot, UInt256.ZERO);
      return TRUE;
    }
  }

  @Override
  public long gasRequirement(final Bytes input) {
    if (input.size() < 4) {
      return 0;
    }
    final Bytes function = input.slice(0, 4);
    if (function.equals(OWNER_SIGNATURE)
        || function.equals(INITIALIZED_SIGNATURE)
        || function.equals(DISCOVERY_SIGNATURE)
        || function.equals(CONTAINS_SIGNATURE)) {
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
    if (input.size() < 4) {
      return PrecompileContractResult.halt(
          null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
    } else {
      final boolean isStaticCall = messageFrame.isStatic();
      final Bytes function = input.slice(0, 4);
      final Bytes calldata = input.slice(4);
      final WorldUpdater worldUpdater = messageFrame.getWorldUpdater();
      final Address senderAddress = messageFrame.getSenderAddress();
      final MutableAccount precompile = worldUpdater.getOrCreate(Address.ADDRESS_REGISTRY);
      if (function.equals(OWNER_SIGNATURE)) {
        return PrecompileContractResult.success(owner(precompile));
      } else if (function.equals(INITIALIZED_SIGNATURE)) {
        return PrecompileContractResult.success(initialized(precompile));
      } else if (function.equals(INITIALIZE_OWNER_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(initializeOwner(precompile, calldata));
      } else if (function.equals(TRANSFER_OWNERSHIP_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(
            transferOwnership(precompile, senderAddress, calldata));
      } else if (function.equals(CONTAINS_SIGNATURE)) {
        return PrecompileContractResult.success(contains(precompile, calldata));
      } else if (function.equals(DISCOVERY_SIGNATURE)) {
        return PrecompileContractResult.success(discovery(precompile, calldata));
      } else if (function.equals(ADD_TO_REGISTRY_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(addToRegistry(precompile, senderAddress, calldata));
      } else if (function.equals(REMOVE_FROM_REGISTRY_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(
            removeFromRegistry(precompile, senderAddress, calldata));
      } else {
        LOG.debug("Failed function {} not found", function);
        return PrecompileContractResult.halt(
            null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
      }
    }
  }
}
