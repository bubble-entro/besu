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

/**
 * RevenueRatio Precompiled Contract .
 *
 * <p>Changes from V1:
 * <ul>
 *   <li>Slot 3 renamed from contractRatio to senderRatio (cashback to sender model)</li>
 *   <li>Added calldata bounds checks on initializeOwner, transferOwnership</li>
 *   <li>setRevenueRatio already has calldata.size() < 128 check (retained)</li>
 * </ul>
 */
public class RevenueRatioPrecompiledContract extends AbstractPrecompiledContract {
  private static final Logger LOG =
      LoggerFactory.getLogger(RevenueRatioPrecompiledContract.class);

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

  /** RevenueRatio — : senderRatio replaces contractRatio */
  private static final Bytes SENDER_RATIO_SIGNATURE =
      Hash.keccak256(Bytes.of("senderRatio()".getBytes(UTF_8))).slice(0, 4);

  private static final Bytes COINBASE_RATIO_SIGNATURE =
      Hash.keccak256(Bytes.of("coinbaseRatio()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes PROVIDER_RATIO_SIGNATURE =
      Hash.keccak256(Bytes.of("providerRatio()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes TREASURY_RATIO_SIGNATURE =
      Hash.keccak256(Bytes.of("treasuryRatio()".getBytes(UTF_8))).slice(0, 4);
  private static final Bytes SET_REVENUE_RATIO_SIGNATURE =
      Hash.keccak256(Bytes.of("setRevenueRatio(uint8,uint8,uint8,uint8)".getBytes(UTF_8)))
          .slice(0, 4);

  /** Storage Layout */
  private static final UInt256 INIT_SLOT = UInt256.ZERO;

  private static final UInt256 OWNER_SLOT = UInt256.ONE;

  private static final UInt256 STATUS_SLOT = UInt256.valueOf(2L);

  /** : Slot 3 is senderRatio (cashback to tx sender) instead of contractRatio */
  private static final UInt256 SENDER_RATIO_SLOT = UInt256.valueOf(3L);

  private static final UInt256 COINBASE_RATIO_SLOT = UInt256.valueOf(4L);

  private static final UInt256 PROVIDER_RATIO_SLOT = UInt256.valueOf(5L);

  private static final UInt256 TREASURY_RATIO_SLOT = UInt256.valueOf(6L);

  /** Returns */
  private static final Bytes FALSE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000");

  private static final Bytes TRUE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001");

  public RevenueRatioPrecompiledContract(final GasCalculator gasCalculator) {
    super("RevenueRatioPrecompiledContract", gasCalculator);
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
    if (calldata.size() < 32) {
      return FALSE;
    }
    if (initialized(contract).equals(TRUE)) {
      return FALSE;
    } else {
      final UInt256 initialOwner = UInt256.fromBytes(calldata.slice(0, 32));
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
    if (calldata.size() < 32) {
      return FALSE;
    }
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      final UInt256 newOwner = UInt256.fromBytes(calldata.slice(0, 32));
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

  private Bytes senderRatio(final MutableAccount contract) {
    return contract.getStorageValue(SENDER_RATIO_SLOT);
  }

  private Bytes coinbaseRatio(final MutableAccount contract) {
    return contract.getStorageValue(COINBASE_RATIO_SLOT);
  }

  private Bytes providerRatio(final MutableAccount contract) {
    return contract.getStorageValue(PROVIDER_RATIO_SLOT);
  }

  private Bytes treasuryRatio(final MutableAccount contract) {
    return contract.getStorageValue(TREASURY_RATIO_SLOT);
  }

  private Bytes setRevenueRatio(
      final MutableAccount contract, final Address senderAddress, final Bytes calldata) {
    if (calldata.size() < 128) {
      return FALSE;
    }
    if (onlyOwner(contract, senderAddress).isZero()) {
      return FALSE;
    } else {
      final UInt256 newSenderRatio = UInt256.fromBytes(calldata.slice(0, 32));
      final UInt256 newCoinbaseRatio = UInt256.fromBytes(calldata.slice(32, 32));
      final UInt256 newProviderRatio = UInt256.fromBytes(calldata.slice(64, 32));
      final UInt256 newTreasuryRatio = UInt256.fromBytes(calldata.slice(96, 32));
      LOG.debug("newSenderRatio {}", newSenderRatio);
      LOG.debug("newCoinbaseRatio {}", newCoinbaseRatio);
      LOG.debug("newProviderRatio {}", newProviderRatio);
      LOG.debug("newTreasuryRatio {}", newTreasuryRatio);
      final UInt256 totalRatio =
          newSenderRatio.add(newCoinbaseRatio).add(newProviderRatio).add(newTreasuryRatio);
      if (!totalRatio.equals(UInt256.valueOf(100L))) {
        return FALSE; // Ratios must sum exactly to 100
      }
      contract.setStorageValue(SENDER_RATIO_SLOT, newSenderRatio);
      contract.setStorageValue(COINBASE_RATIO_SLOT, newCoinbaseRatio);
      contract.setStorageValue(PROVIDER_RATIO_SLOT, newProviderRatio);
      contract.setStorageValue(TREASURY_RATIO_SLOT, newTreasuryRatio);

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
        || function.equals(SENDER_RATIO_SIGNATURE)
        || function.equals(COINBASE_RATIO_SIGNATURE)
        || function.equals(PROVIDER_RATIO_SIGNATURE)
        || function.equals(TREASURY_RATIO_SIGNATURE)) {
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
      final MutableAccount precompile = worldUpdater.getOrCreate(Address.REVENUE_RATIO);
      if (function.equals(OWNER_SIGNATURE)) {
        return PrecompileContractResult.success(owner(precompile));
      } else if (function.equals(INITIALIZED_SIGNATURE)) {
        return PrecompileContractResult.success(initialized(precompile));
      } else if (function.equals(INITIALIZE_OWNER_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(initializeOwner(precompile, calldata));
      } else if (function.equals(TRANSFER_OWNERSHIP_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(
            transferOwnership(precompile, senderAddress, calldata));
      } else if (function.equals(STATUS_SIGNATURE)) {
        return PrecompileContractResult.success(status(precompile));
      } else if (function.equals(ENABLE_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(enable(precompile, senderAddress));
      } else if (function.equals(DISABLE_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(disable(precompile, senderAddress));
      } else if (function.equals(SENDER_RATIO_SIGNATURE)) {
        return PrecompileContractResult.success(senderRatio(precompile));
      } else if (function.equals(COINBASE_RATIO_SIGNATURE)) {
        return PrecompileContractResult.success(coinbaseRatio(precompile));
      } else if (function.equals(PROVIDER_RATIO_SIGNATURE)) {
        return PrecompileContractResult.success(providerRatio(precompile));
      } else if (function.equals(TREASURY_RATIO_SIGNATURE)) {
        return PrecompileContractResult.success(treasuryRatio(precompile));
      } else if (function.equals(SET_REVENUE_RATIO_SIGNATURE) && !isStaticCall) {
        return PrecompileContractResult.success(
            setRevenueRatio(precompile, senderAddress, calldata));
      } else {
        LOG.debug("Failed function {} not found", function);
        return PrecompileContractResult.halt(
            null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
      }
    }
  }
}
