/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.mainnet;

import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.hyperledger.besu.evm.internal.Words.clampedAdd;
import static org.hyperledger.besu.evm.worldstate.CodeDelegationHelper.getTarget;
import static org.hyperledger.besu.evm.worldstate.CodeDelegationHelper.hasCodeDelegation;

import org.hyperledger.besu.datatypes.AccessListEntry;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.ProcessableBlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.feemarket.CoinbaseFeePriceCalculator;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.AccessLocationTracker;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.PartialBlockAccessView;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.ethereum.trie.MerkleTrieException;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.blockhash.BlockHashLookup;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.processor.AbstractMessageProcessor;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.CodeDelegationHelper;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainnetTransactionProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(MainnetTransactionProcessor.class);

  private static final Bytes FALSE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000");

  private static final UInt256 FEE_GRANT_FLAG_STORAGE =
      UInt256.fromHexString("0x330bb6449068d17e3815a045685a05a106741a6e960986b3c72eb86cb692da00");

  private static final UInt256 PRECOMPILE_STORAGE_SLOT = UInt256.valueOf(2L);

  private static final Set<Address> EMPTY_ADDRESS_SET = Set.of();

  protected final GasCalculator gasCalculator;

  protected final TransactionValidatorFactory transactionValidatorFactory;

  private final ContractCreationProcessor contractCreationProcessor;

  private final MessageCallProcessor messageCallProcessor;

  private final int maxStackSize;

  private final boolean clearEmptyAccounts;

  protected final boolean warmCoinbase;

  protected final FeeMarket feeMarket;
  private final CoinbaseFeePriceCalculator coinbaseFeePriceCalculator;

  private final Optional<CodeDelegationProcessor> maybeCodeDelegationProcessor;

  private MainnetTransactionProcessor(
      final GasCalculator gasCalculator,
      final TransactionValidatorFactory transactionValidatorFactory,
      final ContractCreationProcessor contractCreationProcessor,
      final MessageCallProcessor messageCallProcessor,
      final boolean clearEmptyAccounts,
      final boolean warmCoinbase,
      final int maxStackSize,
      final FeeMarket feeMarket,
      final CoinbaseFeePriceCalculator coinbaseFeePriceCalculator,
      final CodeDelegationProcessor maybeCodeDelegationProcessor) {
    this.gasCalculator = gasCalculator;
    this.transactionValidatorFactory = transactionValidatorFactory;
    this.contractCreationProcessor = contractCreationProcessor;
    this.messageCallProcessor = messageCallProcessor;
    this.clearEmptyAccounts = clearEmptyAccounts;
    this.warmCoinbase = warmCoinbase;
    this.maxStackSize = maxStackSize;
    this.feeMarket = feeMarket;
    this.coinbaseFeePriceCalculator = coinbaseFeePriceCalculator;
    this.maybeCodeDelegationProcessor = Optional.ofNullable(maybeCodeDelegationProcessor);
  }

  /**
   * Applies a transaction to the current system state.
   *
   * @param worldState The current world state
   * @param blockHeader The current block header
   * @param transaction The transaction to process
   * @param miningBeneficiary The address which is to receive the transaction fee
   * @param blockHashLookup The {@link BlockHashLookup} to use for BLOCKHASH operations
   * @param transactionValidationParams Validation parameters that will be used by the {@link
   *     MainnetTransactionValidator}
   * @return the transaction result
   * @see MainnetTransactionValidator
   * @see TransactionValidationParams
   */
  public TransactionProcessingResult processTransaction(
      final WorldUpdater worldState,
      final ProcessableBlockHeader blockHeader,
      final Transaction transaction,
      final Address miningBeneficiary,
      final BlockHashLookup blockHashLookup,
      final TransactionValidationParams transactionValidationParams,
      final Wei blobGasPrice) {
    return processTransaction(
        worldState,
        blockHeader,
        transaction,
        miningBeneficiary,
        OperationTracer.NO_TRACING,
        blockHashLookup,
        transactionValidationParams,
        blobGasPrice);
  }

  /**
   * Applies a transaction to the current system state.
   *
   * @param worldState The current world state
   * @param blockHeader The current block header
   * @param transaction The transaction to process
   * @param miningBeneficiary The address which is to receive the transaction fee
   * @param operationTracer The tracer to record results of each EVM operation
   * @param blockHashLookup The {@link BlockHashLookup} to use for BLOCKHASH operations
   * @return the transaction result
   */
  public TransactionProcessingResult processTransaction(
      final WorldUpdater worldState,
      final ProcessableBlockHeader blockHeader,
      final Transaction transaction,
      final Address miningBeneficiary,
      final OperationTracer operationTracer,
      final BlockHashLookup blockHashLookup,
      final Wei blobGasPrice) {
    return processTransaction(
        worldState,
        blockHeader,
        transaction,
        miningBeneficiary,
        operationTracer,
        blockHashLookup,
        ImmutableTransactionValidationParams.builder().build(),
        blobGasPrice);
  }

  public TransactionProcessingResult processTransaction(
      final WorldUpdater worldState,
      final ProcessableBlockHeader blockHeader,
      final Transaction transaction,
      final Address miningBeneficiary,
      final OperationTracer operationTracer,
      final BlockHashLookup blockHashLookup,
      final TransactionValidationParams transactionValidationParams,
      final Wei blobGasPrice) {
    return processTransaction(
        worldState,
        blockHeader,
        transaction,
        miningBeneficiary,
        operationTracer,
        blockHashLookup,
        ImmutableTransactionValidationParams.builder().build(),
        blobGasPrice,
        Optional.empty());
  }

  public TransactionProcessingResult processTransaction(
      final WorldUpdater worldState,
      final ProcessableBlockHeader blockHeader,
      final Transaction transaction,
      final Address miningBeneficiary,
      final OperationTracer operationTracer,
      final BlockHashLookup blockHashLookup,
      final TransactionValidationParams transactionValidationParams,
      final Wei blobGasPrice,
      final Optional<AccessLocationTracker> accessLocationTracker) {
    try {
      final var transactionValidator = transactionValidatorFactory.get();
      LOG.trace("Starting execution of {}", transaction);
      ValidationResult<TransactionInvalidReason> validationResult =
          transactionValidator.validate(
              transaction,
              blockHeader.getBaseFee(),
              Optional.ofNullable(blobGasPrice),
              transactionValidationParams);
      // Make sure the transaction is intrinsically valid before trying to
      // compare against a sender account (because the transaction may not
      // be signed correctly to extract the sender).
      if (!validationResult.isValid()) {
        LOG.debug("Invalid transaction: {}", validationResult.getErrorMessage());
        return TransactionProcessingResult.invalid(validationResult);
      }

      final Address senderAddress = transaction.getSender();
      final MutableAccount sender = worldState.getOrCreateSenderAccount(senderAddress);
      accessLocationTracker.ifPresent(t -> t.addTouchedAccount(senderAddress));

      validationResult =
          transactionValidator.validateForSender(transaction, sender, transactionValidationParams);
      if (!validationResult.isValid()) {
        LOG.debug("Invalid transaction: {}", validationResult.getErrorMessage());
        return TransactionProcessingResult.invalid(validationResult);
      }

      Wei spendLimit = Wei.ZERO;
      Wei periodLimit = Wei.ZERO;
      Wei periodCanSpend = Wei.ZERO;
      UInt256 periodReset = UInt256.ZERO;
      UInt256 latestTransaction = UInt256.ZERO;
      UInt256 rootStorageSlot = UInt256.ZERO;
      UInt256 period = UInt256.ZERO;
      Address granterAddress = Address.ZERO;
      MutableAccount granter = worldState.getOrCreate(Address.ZERO);
      boolean isPeriodic = false;
      boolean isWildcard = false;

      boolean useGrant = !sender.getStorageValue(FEE_GRANT_FLAG_STORAGE).isZero();
      // && (sender.getBalance()).isZero();
      final MutableAccount feeGrant = worldState.getOrCreate(Address.GASFEE_GRANT);
      // Check is the transaction send from granted account.
      if (useGrant) {
        final UInt256 rootSlotForAll = getRootSlotOfGasFeeGrant(senderAddress, Address.ZERO);

        Address to = Address.ZERO;
        if (transaction.getTo().isPresent()) {
          to = transaction.getTo().get();
        }
        final UInt256 rootSlotForProgram = getRootSlotOfGasFeeGrant(senderAddress, to);

        final UInt256 allowanceForAll = feeGrant.getStorageValue(rootSlotForAll.add(1L));
        final UInt256 allowanceForProgram = feeGrant.getStorageValue(rootSlotForProgram.add(1L));
        final UInt256 allowance =
            !allowanceForAll.isZero()
                ? allowanceForAll
                : !allowanceForProgram.isZero() ? allowanceForProgram : UInt256.ZERO;
        final UInt256 blockNumber = UInt256.valueOf(blockHeader.getNumber());
        if (!allowance.isZero()) {
          rootStorageSlot =
              !allowanceForAll.isZero()
                  ? rootSlotForAll
                  : !allowanceForProgram.isZero() ? rootSlotForProgram : UInt256.ZERO;
          final UInt256 endTime = feeGrant.getStorageValue(rootStorageSlot.add(6L));
          // Check if the granted is expired or not.
          if (!endTime.isZero() && blockNumber.compareTo(endTime) > 0) {
            LOG.debug("Fee grant expired, falling back to sender");
            useGrant = false;
          } else {
            granterAddress = Address.wrap(feeGrant.getStorageValue(rootStorageSlot).slice(12, 20));
            spendLimit = Wei.of((feeGrant.getStorageValue(rootStorageSlot.add(2L))));
            // Check is allowance type is periodic.
            if (feeGrant.getStorageValue((rootStorageSlot.add(1L))).equals(UInt256.valueOf(2L))) {
              // Get period can spend value.
              periodReset = feeGrant.getStorageValue(rootStorageSlot.add(5L));
              latestTransaction = feeGrant.getStorageValue(rootStorageSlot.add(7L));
              period = feeGrant.getStorageValue(rootStorageSlot.add(8L));
              final UInt256 cycles = (blockNumber.subtract(periodReset)).divide(period);
              if (!cycles.isZero()) {
                periodReset = periodReset.add(cycles.multiply(period));
              }
              if (latestTransaction.add(period).compareTo(periodReset) < 0) {
                periodLimit = Wei.of(feeGrant.getStorageValue(rootStorageSlot.add(3L)));
                periodCanSpend = periodLimit;
              } else {
                periodCanSpend = Wei.of(feeGrant.getStorageValue(rootStorageSlot.add(4L)));
              }
              isPeriodic = true;
            } else if (feeGrant
                .getStorageValue(rootStorageSlot.add(1L))
                .equals(UInt256.valueOf(3L))) {
              // Wildcard grant — no spend/period/expiry checks
              isWildcard = true;
            }
          }
          if (useGrant) {
            granter = worldState.getOrCreate(granterAddress);

            final Wei MINIMUM_GRANTER_BALANCE = Wei.of(10_000_000_000_000_000L); // 0.01 ETH
    
            if (granter.getBalance().compareTo(MINIMUM_GRANTER_BALANCE) < 0) {
                // granter เงินหมด — clear flag แล้ว fallback
                LOG.debug("Granter {} balance below minimum, clearing grant flag", granterAddress);
                
                final MutableAccount senderAccount = worldState.getOrCreate(senderAddress);
                senderAccount.setStorageValue(FEE_GRANT_FLAG_STORAGE, UInt256.ZERO);
        
                useGrant = false; // fallback to sender transaction
            }
          }
        } else {
          useGrant = false;
        }
      }

      operationTracer.tracePrepareTransaction(worldState, transaction);

      final Set<Address> eip2930WarmAddressList = new HashSet<>(Address.SIZE);

      final long previousNonce = sender.incrementNonce();
      LOG.trace(
          "Incremented sender {} nonce ({} -> {})",
          senderAddress,
          previousNonce,
          sender.getNonce());

      final Wei transactionGasPrice =
          feeMarket.getTransactionPriceCalculator().price(transaction, blockHeader.getBaseFee());

      final long blobGas = gasCalculator.blobGasCost(transaction.getBlobCount());

      final Wei upfrontGasCost =
          transaction.getUpfrontGasCost(transactionGasPrice, blobGasPrice, blobGas);

      if (useGrant && !isWildcard) {
        if (upfrontGasCost.compareTo(granter.getBalance()) > 0
            || upfrontGasCost.compareTo(spendLimit) > 0
            || (isPeriodic && upfrontGasCost.compareTo(periodCanSpend) > 0)) {
          LOG.debug(
              "Fee grant up-front cost exceeds allowance, falling back to sender");
          useGrant = false;
        }
      }

      Wei previousBalance;
      if (useGrant) {
        // deducted balance from granter account.
        previousBalance = granter.decrementBalance(upfrontGasCost);

        LOG.trace(
            "Deducted granter {} upfront gas cost {} ({} -> {})",
            granterAddress,
            upfrontGasCost,
            previousBalance,
            granter.getBalance());
      } else {
        if (sender.getBalance().compareTo(upfrontGasCost) < 0) {
          LOG.trace("Insufficient balance for upfront gas cost");
          return TransactionProcessingResult.invalid(
              ValidationResult.invalid(
                  TransactionInvalidReason.UPFRONT_COST_EXCEEDS_BALANCE,
                  "Sender " + senderAddress + " has insufficient balance to pay for gas"));
        }
        try {
          previousBalance = sender.decrementBalance(upfrontGasCost);
          LOG.trace(
              "Deducted sender {} upfront gas cost {} ({} -> {})",
              senderAddress,
              upfrontGasCost,
              previousBalance,
              sender.getBalance());
        } catch (final IllegalStateException ise) {
          if (transactionValidationParams.allowUnderpriced()) {
            LOG.trace("Allowing account balance underflow as requested");
          } else {
            throw ise;
          }
        }
      }

      long codeDelegationRefund = 0L;
      if (transaction.getType().equals(TransactionType.DELEGATE_CODE)) {
        if (maybeCodeDelegationProcessor.isEmpty()) {
          throw new RuntimeException("Code delegation processor is required for 7702 transactions");
        }

        final WorldUpdater delegationUpdater = worldState.updater();
        final CodeDelegationResult codeDelegationResult =
            maybeCodeDelegationProcessor
                .get()
                .process(delegationUpdater, transaction, accessLocationTracker);
        eip2930WarmAddressList.addAll(codeDelegationResult.accessedDelegatorAddresses());
        codeDelegationRefund =
            gasCalculator.calculateDelegateCodeGasRefund(
                (codeDelegationResult.alreadyExistingDelegators()));
        delegationUpdater.commit();
      }

      final List<AccessListEntry> eip2930AccessListEntries =
          transaction.getAccessList().orElse(List.of());
      // we need to keep a separate hash set of addresses in case they specify no storage.
      // No-storage is a common pattern, especially for Externally Owned Accounts
      final Multimap<Address, Bytes32> eip2930StorageList = HashMultimap.create();
      int accessListStorageCount = 0;
      for (final var entry : eip2930AccessListEntries) {
        final Address address = entry.address();
        eip2930WarmAddressList.add(address);
        final List<Bytes32> storageKeys = entry.storageKeys();
        eip2930StorageList.putAll(address, storageKeys);
        accessListStorageCount += storageKeys.size();
      }
      if (warmCoinbase) {
        eip2930WarmAddressList.add(miningBeneficiary);
      }

      final long accessListGas =
          gasCalculator.accessListGasCost(eip2930AccessListEntries.size(), accessListStorageCount);
      final long codeDelegationGas =
          gasCalculator.delegateCodeGasCost(transaction.codeDelegationListSize());
      final long intrinsicGas =
          gasCalculator.transactionIntrinsicGasCost(
              transaction, clampedAdd(accessListGas, codeDelegationGas));

      final long gasAvailable = transaction.getGasLimit() - intrinsicGas;
      LOG.trace(
          "Gas available for execution {} = {} - {} (limit - intrinsic)",
          gasAvailable,
          transaction.getGasLimit(),
          intrinsicGas);

      final WorldUpdater worldUpdater = worldState.updater();

      operationTracer.traceStartTransaction(worldUpdater, transaction);

      final MessageFrame.Builder commonMessageFrameBuilder =
          MessageFrame.builder()
              .maxStackSize(maxStackSize)
              .worldUpdater(worldUpdater.updater())
              .initialGas(gasAvailable)
              .originator(senderAddress)
              .gasPrice(transactionGasPrice)
              .blobGasPrice(blobGasPrice)
              .sender(senderAddress)
              .value(transaction.getValue())
              .apparentValue(transaction.getValue())
              .blockValues(blockHeader)
              .completer(__ -> {})
              .miningBeneficiary(miningBeneficiary)
              .blockHashLookup(blockHashLookup)
              .eip2930AccessListWarmStorage(eip2930StorageList);

      accessLocationTracker.ifPresent(commonMessageFrameBuilder::eip7928AccessList);

      if (transaction.getVersionedHashes().isPresent()) {
        commonMessageFrameBuilder.versionedHashes(
            Optional.of(transaction.getVersionedHashes().get().stream().toList()));
      } else {
        commonMessageFrameBuilder.versionedHashes(Optional.empty());
      }

      final MessageFrame initialFrame;
      if (transaction.isContractCreation()) {
        final Address contractAddress =
            Address.contractAddress(senderAddress, sender.getNonce() - 1L);
        accessLocationTracker.ifPresent(t -> t.addTouchedAccount(contractAddress));

        final Bytes initCodeBytes = transaction.getPayload();
        Code code = new Code(initCodeBytes);
        initialFrame =
            commonMessageFrameBuilder
                .type(MessageFrame.Type.CONTRACT_CREATION)
                .address(contractAddress)
                .contract(contractAddress)
                .inputData(initCodeBytes.slice(code.getSize()))
                .code(code)
                .eip2930AccessListWarmAddresses(eip2930WarmAddressList)
                .build();
      } else {
        @SuppressWarnings("OptionalGetWithoutIsPresent") // isContractCall tests isPresent
        final Address to = transaction.getTo().get();
        accessLocationTracker.ifPresent(t -> t.addTouchedAccount(to));
        final Code code =
            processCodeFromAccount(
                worldState, eip2930WarmAddressList, worldState.get(to), accessLocationTracker);

        initialFrame =
            commonMessageFrameBuilder
                .type(MessageFrame.Type.MESSAGE_CALL)
                .address(to)
                .contract(to)
                .inputData(transaction.getPayload())
                .code(code)
                .eip2930AccessListWarmAddresses(eip2930WarmAddressList)
                .build();
      }
      Deque<MessageFrame> messageFrameStack = initialFrame.getMessageFrameStack();

      while (!messageFrameStack.isEmpty()) {
        process(messageFrameStack.peekFirst(), operationTracer);
      }

      if (initialFrame.getState() == MessageFrame.State.COMPLETED_SUCCESS) {
        worldUpdater.commit();
      } else {
        if (initialFrame.getExceptionalHaltReason().isPresent()) {
          validationResult =
              ValidationResult.invalid(
                  TransactionInvalidReason.EXECUTION_HALTED,
                  initialFrame.getExceptionalHaltReason().get().getDescription());
        }
      }

      // TODO SLD are the log correct following EIP-7623?
      if (LOG.isTraceEnabled()) {
        LOG.trace(
            "Gas used by transaction: {}, by message call/contract creation: {}",
            transaction.getGasLimit() - initialFrame.getRemainingGas(),
            gasAvailable - initialFrame.getRemainingGas());
      }

      // Refund the sender by what we should and pay the miner fee (note that we're doing them one
      // after the other so that if it is the same account somehow, we end up with the right result)
      final long refundedGas =
          gasCalculator.calculateGasRefund(transaction, initialFrame, codeDelegationRefund);
      final Wei refundedWei = transactionGasPrice.multiply(refundedGas);

      // Refund the granter if the transaction is fee grant transaction.
      Wei balancePriorToRefund;
      if (useGrant) {
        balancePriorToRefund = granter.getBalance();
        granter.incrementBalance(refundedWei);
        LOG.atTrace()
            .setMessage("refunded granter {}  {} wei ({} -> {})")
            .addArgument(granterAddress)
            .addArgument(refundedWei)
            .addArgument(balancePriorToRefund)
            .addArgument(sender.getBalance())
            .log();
      } else {
        balancePriorToRefund = sender.getBalance();
        sender.incrementBalance(refundedWei);
        LOG.atTrace()
            .setMessage("refunded sender {}  {} wei ({} -> {})")
            .addArgument(senderAddress)
            .addArgument(refundedWei)
            .addArgument(balancePriorToRefund)
            .addArgument(sender.getBalance())
            .log();
      }

      final long gasUsedByTransaction = transaction.getGasLimit() - initialFrame.getRemainingGas();

      // update the coinbase
      final long usedGas = transaction.getGasLimit() - refundedGas;
      final CoinbaseFeePriceCalculator coinbaseCalculator;
      if (blockHeader.getBaseFee().isPresent()) {
        final Wei baseFee = blockHeader.getBaseFee().get();
        final boolean gasPriceBelowBaseFee = transactionGasPrice.compareTo(baseFee) < 0;
        if (transactionValidationParams.allowUnderpriced()) {
          coinbaseCalculator =
              gasPriceBelowBaseFee ? (a, b, c) -> Wei.ZERO : coinbaseFeePriceCalculator;
        } else {
          if (gasPriceBelowBaseFee) {
            final Optional<PartialBlockAccessView> partialBlockAccessView =
                accessLocationTracker.map(
                    tracker -> tracker.createPartialBlockAccessView(worldState));
            return TransactionProcessingResult.failed(
                gasUsedByTransaction,
                refundedGas,
                usedGas,
                ValidationResult.invalid(
                    TransactionInvalidReason.TRANSACTION_PRICE_TOO_LOW,
                    "transaction price must be greater than base fee"),
                Optional.empty(),
                Optional.empty(),
                partialBlockAccessView);
          }
          coinbaseCalculator = coinbaseFeePriceCalculator;
        }
      } else {
        coinbaseCalculator = CoinbaseFeePriceCalculator.frontier();
      }

      final Wei coinbaseWeiDelta =
          coinbaseCalculator.price(usedGas, transactionGasPrice, blockHeader.getBaseFee());

      operationTracer.traceBeforeRewardTransaction(worldUpdater, transaction, coinbaseWeiDelta);

      final Bytes revenueRatioStatus = getStorageAtFromRevenueRatio(worldUpdater, 2L);

      if (!coinbaseWeiDelta.isZero() || !clearEmptyAccounts) {
        final var coinbase = worldState.getOrCreate(miningBeneficiary);
        accessLocationTracker.ifPresent(t -> t.addTouchedAccount(miningBeneficiary));
        if (revenueRatioStatus.equals(FALSE)) {
          coinbase.incrementBalance(coinbaseWeiDelta);
        } else {
          final Address providerAddress = getProviderOf(worldUpdater, senderAddress);
          final var provider = worldState.getOrCreate(providerAddress);
          final var treasury = worldState.getOrCreate(getTreasuryAddress(worldUpdater));
          if (!initialFrame.getInputData().isEmpty() && !transaction.isContractCreation()) {
            final Address contractProviderAddress =
                getProviderOf(worldUpdater, transaction.getTo().get());
            final var contractProvider = worldState.getOrCreate(contractProviderAddress);
            Wei feeForContract =
                coinbaseWeiDelta
                    .multiply(Wei.wrap(getStorageAtFromRevenueRatio(worldUpdater, 3L)))
                    .divide(100L);
            Wei feeForProvider =
                coinbaseWeiDelta
                    .multiply(Wei.wrap(getStorageAtFromRevenueRatio(worldUpdater, 4L)))
                    .divide(100L);
            final Wei feeForTreasury =
                coinbaseWeiDelta
                    .multiply(Wei.wrap(getStorageAtFromRevenueRatio(worldUpdater, 5L)))
                    .divide(100L);
            if (contractProviderAddress.equals(Address.ZERO)) {
              feeForContract = Wei.ZERO;
            }
            if (providerAddress.equals(Address.ZERO)) {
              feeForProvider = Wei.ZERO;
            }
            if (!feeForContract.isZero()) {
              contractProvider.incrementBalance(feeForContract);
              LOG.debug(
                  "Transaction fee distribute to contract provider at {}: {} wei",
                  contractProviderAddress,
                  feeForContract);
            }
            if (!feeForProvider.isZero()) {
              provider.incrementBalance(feeForProvider);
              LOG.debug(
                  "Transaction fee distribute to provider at {}: {} wei", providerAddress, feeForProvider);
            }
            final Wei feeForCoinbase =
                coinbaseWeiDelta
                    .subtract(feeForContract)
                    .subtract(feeForTreasury)
                    .subtract(feeForProvider);
            treasury.incrementBalance(feeForTreasury);
            coinbase.incrementBalance(feeForCoinbase);
            LOG.debug(
                "Transaction fee distribute to treasury at {}: {} wei", treasury.getAddress(), feeForTreasury);
            LOG.debug(
                "Transaction fee distribute to coinbase {}: {} wei", coinbase.getAddress(), feeForCoinbase);
          } else {
            final Wei fee = coinbaseWeiDelta.divide(2L);
            coinbase.incrementBalance(fee);
            treasury.incrementBalance(fee);
            LOG.debug("Transaction fee distribute to coinbase {}: {} wei", coinbase.getAddress(), fee);
            LOG.debug("Transaction fee distribute to treasury {}: {} wei", treasury.getAddress(), fee);
          }
        }
      }

      // Check if granted transaction then update latest transaction.
      // Check if granted transaction then update latest transaction.
      if (useGrant && !isWildcard) {
        // Update spendLimit
        spendLimit = spendLimit.subtract(coinbaseWeiDelta);
        feeGrant.setStorageValue(
            rootStorageSlot.add(2L), UInt256.fromHexString(spendLimit.toHexString()));

        if (isPeriodic) {
          if (latestTransaction.add(period).compareTo(periodReset) < 0) {
            periodCanSpend = periodLimit.subtract(coinbaseWeiDelta);
          } else {
            periodCanSpend = periodCanSpend.subtract(coinbaseWeiDelta);
          }
          feeGrant.setStorageValue(
              rootStorageSlot.add(4L), UInt256.fromHexString(periodCanSpend.toHexString()));
          feeGrant.setStorageValue(
              rootStorageSlot.add(7L), UInt256.valueOf(blockHeader.getNumber()));
        }
      }

      operationTracer.traceEndTransaction(
          worldState.updater(),
          transaction,
          initialFrame.getState() == MessageFrame.State.COMPLETED_SUCCESS,
          initialFrame.getOutputData(),
          initialFrame.getLogs(),
          gasUsedByTransaction,
          initialFrame.getSelfDestructs(),
          0L);

      initialFrame.getSelfDestructs().forEach(worldState::deleteAccount);

      if (clearEmptyAccounts) {
        worldState.clearAccountsThatAreEmpty();
      }

      final Optional<PartialBlockAccessView> partialBlockAccessView =
          accessLocationTracker.map(tracker -> tracker.createPartialBlockAccessView(worldState));

      if (initialFrame.getState() == MessageFrame.State.COMPLETED_SUCCESS) {
        return TransactionProcessingResult.successful(
            initialFrame.getLogs(),
            gasUsedByTransaction,
            refundedGas,
            usedGas,
            initialFrame.getOutputData(),
            partialBlockAccessView,
            validationResult);
      } else {
        if (initialFrame.getExceptionalHaltReason().isPresent()) {
          LOG.debug(
              "Transaction {} processing halted: {}",
              transaction.getHash(),
              initialFrame.getExceptionalHaltReason().get());
        }
        if (initialFrame.getRevertReason().isPresent()) {
          LOG.debug(
              "Transaction {} reverted: {}",
              transaction.getHash(),
              initialFrame.getRevertReason().get());
        }
        return TransactionProcessingResult.failed(
            gasUsedByTransaction,
            refundedGas,
            usedGas,
            validationResult,
            initialFrame.getRevertReason(),
            initialFrame.getExceptionalHaltReason(),
            partialBlockAccessView);
      }
    } catch (final MerkleTrieException re) {
      operationTracer.traceEndTransaction(
          worldState.updater(),
          transaction,
          false,
          Bytes.EMPTY,
          List.of(),
          0,
          EMPTY_ADDRESS_SET,
          0L);

      // need to throw to trigger the heal
      throw re;
    } catch (final RuntimeException re) {
      final var cause = re.getCause();
      // in case of an interruption then just return without calling any other tracing method
      if (cause != null && cause instanceof InterruptedException) {
        LOG.atDebug()
            .setMessage("Interrupted while processing the transaction with hash {}")
            .addArgument(transaction::getHash)
            .log();
        return TransactionProcessingResult.invalid(
            ValidationResult.invalid(TransactionInvalidReason.EXECUTION_INTERRUPTED));
      }

      operationTracer.traceEndTransaction(
          worldState.updater(),
          transaction,
          false,
          Bytes.EMPTY,
          List.of(),
          0,
          EMPTY_ADDRESS_SET,
          0L);

      LOG.error("Critical Exception Processing Transaction", re);
      return TransactionProcessingResult.invalid(
          ValidationResult.invalid(
              TransactionInvalidReason.INTERNAL_ERROR,
              "Internal Error in Besu - " + re + "\n" + printableStackTraceFromThrowable(re)));
    }
  }

  public void process(final MessageFrame frame, final OperationTracer operationTracer) {
    final AbstractMessageProcessor executor = getMessageProcessor(frame.getType());

    executor.process(frame, operationTracer);
  }

  public AbstractMessageProcessor getMessageProcessor(final MessageFrame.Type type) {
    return switch (type) {
      case MESSAGE_CALL -> messageCallProcessor;
      case CONTRACT_CREATION -> contractCreationProcessor;
    };
  }

  public MessageCallProcessor getMessageCallProcessor() {
    return messageCallProcessor;
  }

  public boolean getClearEmptyAccounts() {
    return clearEmptyAccounts;
  }

  private String printableStackTraceFromThrowable(final RuntimeException re) {
    final StringBuilder builder = new StringBuilder();

    for (final StackTraceElement stackTraceElement : re.getStackTrace()) {
      builder.append("\tat ").append(stackTraceElement.toString()).append("\n");
    }

    return builder.toString();
  }

  private Address getProviderOf(final WorldUpdater worldUpdater, final Address address) {
    final MutableAccount addressRegistry = worldUpdater.getOrCreate(Address.ADDRESS_REGISTRY);
    final Bytes hash = Bytes.concatenate(Bytes32.wrap(PRECOMPILE_STORAGE_SLOT), Bytes32.leftPad(address.getBytes()));
    final UInt256 slot = UInt256.fromBytes(keccak256(hash));
    return Address.wrap(addressRegistry.getStorageValue(slot).slice(12, 20));
  }

  private Address getTreasuryAddress(final WorldUpdater worldUpdater) {
    final MutableAccount treasuryRegistry = worldUpdater.getOrCreate(Address.TREASURY_REGISTRY);
    return Address.wrap(treasuryRegistry.getStorageValue(PRECOMPILE_STORAGE_SLOT).slice(12, 20));
  }

  private Bytes getStorageAtFromRevenueRatio(final WorldUpdater worldUpdater, final long slot) {
    final MutableAccount revenueRatio = worldUpdater.getOrCreate(Address.REVENUE_RATIO);
    return revenueRatio.getStorageValue(UInt256.valueOf(slot));
  }

  private UInt256 getRootSlotOfGasFeeGrant(final Address sender, final Address program) {
    final Bytes32 root = keccak256(Bytes.concatenate(PRECOMPILE_STORAGE_SLOT, Bytes32.leftPad(sender.getBytes())));
    final Bytes32 slot = keccak256(Bytes.concatenate(root, Bytes32.leftPad(program.getBytes())));
    return UInt256.fromBytes(slot);
  }

  private Code processCodeFromAccount(
      final WorldUpdater worldUpdater,
      final Set<Address> warmAddressList,
      final Account contract,
      final Optional<AccessLocationTracker> accessLocationTracker) {
    if (contract == null) {
      return Code.EMPTY_CODE;
    }

    final Hash codeHash = contract.getCodeHash();
    if (codeHash == null || codeHash.equals(Hash.EMPTY)) {
      return Code.EMPTY_CODE;
    }

    if (hasCodeDelegation(contract.getCode())) {
      return delegationTargetCode(worldUpdater, warmAddressList, contract, accessLocationTracker);
    }

    // Bonsai accounts may have a fully cached code, so we use that one
    if (contract.getCodeCache() != null) {
      return contract.getOrCreateCachedCode();
    }

    // Any other account can only use the cached jump dest analysis if available
    return messageCallProcessor.getOrCreateCachedJumpDest(
        contract.getCodeHash(), contract.getCode());
  }

  private Code delegationTargetCode(
      final WorldUpdater worldUpdater,
      final Set<Address> warmAddressList,
      final Account contract,
      final Optional<AccessLocationTracker> accessLocationTracker) {
    // we need to look up the target account and its code, but do NOT charge gas for it
    final CodeDelegationHelper.Target target =
        getTarget(worldUpdater, gasCalculator::isPrecompile, contract, accessLocationTracker);
    warmAddressList.add(target.address());

    return target.code();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private GasCalculator gasCalculator;
    private TransactionValidatorFactory transactionValidatorFactory;
    private ContractCreationProcessor contractCreationProcessor;
    private MessageCallProcessor messageCallProcessor;
    private boolean clearEmptyAccounts;
    private boolean warmCoinbase;
    private int maxStackSize;
    private FeeMarket feeMarket;
    private CoinbaseFeePriceCalculator coinbaseFeePriceCalculator;
    private CodeDelegationProcessor codeDelegationProcessor;

    public Builder gasCalculator(final GasCalculator gasCalculator) {
      this.gasCalculator = gasCalculator;
      return this;
    }

    public Builder transactionValidatorFactory(
        final TransactionValidatorFactory transactionValidatorFactory) {
      this.transactionValidatorFactory = transactionValidatorFactory;
      return this;
    }

    public Builder contractCreationProcessor(
        final ContractCreationProcessor contractCreationProcessor) {
      this.contractCreationProcessor = contractCreationProcessor;
      return this;
    }

    public Builder messageCallProcessor(final MessageCallProcessor messageCallProcessor) {
      this.messageCallProcessor = messageCallProcessor;
      return this;
    }

    public Builder clearEmptyAccounts(final boolean clearEmptyAccounts) {
      this.clearEmptyAccounts = clearEmptyAccounts;
      return this;
    }

    public Builder warmCoinbase(final boolean warmCoinbase) {
      this.warmCoinbase = warmCoinbase;
      return this;
    }

    public Builder maxStackSize(final int maxStackSize) {
      this.maxStackSize = maxStackSize;
      return this;
    }

    public Builder feeMarket(final FeeMarket feeMarket) {
      this.feeMarket = feeMarket;
      return this;
    }

    public Builder coinbaseFeePriceCalculator(
        final CoinbaseFeePriceCalculator coinbaseFeePriceCalculator) {
      this.coinbaseFeePriceCalculator = coinbaseFeePriceCalculator;
      return this;
    }

    public Builder codeDelegationProcessor(
        final CodeDelegationProcessor maybeCodeDelegationProcessor) {
      this.codeDelegationProcessor = maybeCodeDelegationProcessor;
      return this;
    }

    public Builder populateFrom(final MainnetTransactionProcessor processor) {
      this.gasCalculator = processor.gasCalculator;
      this.transactionValidatorFactory = processor.transactionValidatorFactory;
      this.contractCreationProcessor = processor.contractCreationProcessor;
      this.messageCallProcessor = processor.messageCallProcessor;
      this.clearEmptyAccounts = processor.clearEmptyAccounts;
      this.warmCoinbase = processor.warmCoinbase;
      this.maxStackSize = processor.maxStackSize;
      this.feeMarket = processor.feeMarket;
      this.coinbaseFeePriceCalculator = processor.coinbaseFeePriceCalculator;
      this.codeDelegationProcessor = processor.maybeCodeDelegationProcessor.orElse(null);
      return this;
    }

    public MainnetTransactionProcessor build() {
      return new MainnetTransactionProcessor(
          gasCalculator,
          transactionValidatorFactory,
          contractCreationProcessor,
          messageCallProcessor,
          clearEmptyAccounts,
          warmCoinbase,
          maxStackSize,
          feeMarket,
          coinbaseFeePriceCalculator,
          codeDelegationProcessor);
    }
  }
}
