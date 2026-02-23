# Precompile Security Check

This document outlines the security, stability, and logical risks identified in the custom precompiled contracts, along with the test coverage used to verify and mitigate them.

---

## Test Coverage Summary

### GasFeeGrantPrecompiledContract — 13 Tests

| # | Test | Category | Status |
|---|------|----------|--------|
| 1 | `testEmptyInputReturnsHalt` | Input validation | ✅ |
| 2 | `testOwnerReturnsStoredValue` | Read query | ✅ |
| 3 | `testInitializedReturnsZeroWhenNotInitialized` | Read query | ✅ |
| 4 | `testInitializeOwnerSuccess` | Ownership | ✅ |
| 5 | `testInitializeOwnerFailsWhenAlreadyInitialized` | Ownership | ✅ |
| 6 | `testTransferOwnershipSuccess` | Ownership | ✅ |
| 7 | `testTransferOwnershipFailsIfNotOwner` | Access control | ✅ |
| 8 | `testSetFeeGrantSuccess` | Core logic | ✅ |
| 9 | `testSetFeeGrantShortCalldataCausesCrash` | Bound checking | ✅ Fixed |
| 10 | `testSetFeeGrantFailsIfSpendLimitExceedsPeriodLimit` | Logic validation | ✅ |
| 11 | `testPeriodResetUnderflowBug` | Math safety | ✅ Fixed |
| 12 | `testSetFeeGrantFailsIfNotOwner` | Access control | ✅ |
| 13 | `testRevokeFeeGrantSuccess` | Core logic | ✅ |

### NativeMinterPrecompiledContract — 17 Tests

| # | Test | Category | Status |
|---|------|----------|--------|
| 1 | `testEmptyInputReturnsHalt` | Input validation | ✅ |
| 2 | `testOwnerReturnsStoredValue` | Read query | ✅ |
| 3 | `testInitializedReturnsZeroWhenNotInitialized` | Read query | ✅ |
| 4 | `testInitializedReturnsOneAfterInit` | Read query | ✅ |
| 5 | `testInitializeOwnerSuccess` | Ownership | ✅ |
| 6 | `testInitializeOwnerFailsWhenAlreadyInitialized` | Ownership | ✅ |
| 7 | `testInitializeOwnerFailsWithZeroAddress` | Input validation | ✅ |
| 8 | `testInitializeOwnerBlockedInStaticCall` | Access control | ✅ |
| 9 | `testTransferOwnershipSuccess` | Ownership | ✅ |
| 10 | `testTransferOwnershipFailsIfNotOwner` | Access control | ✅ |
| 11 | `testTransferOwnershipFailsWithZeroAddress` | Input validation | ✅ |
| 12 | `testMintSuccess` | Core logic | ✅ |
| 13 | `testMintFailsIfNotOwner` | Access control | ✅ |
| 14 | `testMintFailsWithZeroAmount` | Input validation | ✅ |
| 15 | `testMintFailsToZeroAddress` | Input validation | ✅ |
| 16 | `testMintLargeAmount` | Edge case | ✅ |
| 17 | `testMintShortCalldataCrash` | Bound checking | ⚠️ Crash confirmed |

### Live Crash Proof Script — `test-feegrant-crash.ts`

Tests 6 unprotected read-only GasFeeGrant functions via `eth_call` with 4-byte payloads. Confirmed `IndexOutOfBoundsException` on unpatched nodes.

---

## Risk Metric Overview

| ID | Contract | Vulnerability | Impact | Likelihood | Severity |
|:---|:---------|:-------------|:-------|:-----------|:---------|
| **RISK-01** | GasFeeGrant | Unchecked `.slice()` — Node DoS | High | High | **CRITICAL (9.5)** |
| **RISK-02** | GasFeeGrant | UInt256 underflow in `periodReset` | High | Med | **HIGH (7.5)** |
| **RISK-03** | GasFeeGrant | `spendLimit > periodLimit` bypass | Med | Low | **MEDIUM (4.0)** |
| **RISK-04** | GasFeeGrant | Zero address accepted | Low | Low | **LOW (2.0)** |
| **RISK-05** | NativeMinter | Unchecked `.slice()` in `mint` — Node DoS | High | Med | **HIGH (7.0)** |

---

## RISK-01: IndexOutOfBoundsException on Short Calldata (GasFeeGrant)
**Severity:** CRITICAL  
**Affected functions:** `isGrantedForProgram`, `grant`, `isGrantedForAllProgram`, `periodCanSpend`, `periodReset`, `setFeeGrant`  
**Access control:** None on read-only functions → **anyone can trigger**

Short calldata causes `calldata.slice(12, 20)` to throw unchecked `IndexOutOfBoundsException`, crashing the EVM worker thread.

**Fix applied:** Added `calldata.size() < N` guard at the top of each function:
- `isGrantedForProgram`: ≥ 64 bytes
- `grant`: ≥ 64 bytes
- `isGrantedForAllProgram`: ≥ 32 bytes
- `periodCanSpend`: ≥ 64 bytes
- `periodReset`: ≥ 64 bytes
- `setFeeGrant`: ≥ 224 bytes

---

## RISK-02: UInt256 Underflow in `periodReset`
**Severity:** HIGH  
**Impact:** Returns astronomically large reset block, permanently locking the grant period.

When `blockNumber < resetBlock`, `blockNumber.subtract(resetBlock)` underflows UInt256.

**Fix applied:**
```java
if (blockNumber.compareTo(resetBlock) < 0) {
    return resetBlock;
}
```

---

## RISK-03: `spendLimit` Exceeds `periodLimit`
**Severity:** MEDIUM  
**Impact:** Contradictory grant state where total spend exceeds per-period allowance.

**Fix applied:**
```java
if (spendLimit.compareTo(periodLimit) > 0) {
    return FALSE;
}
```

---

## RISK-04: Zero Address Granter/Grantee
**Severity:** LOW  
**Impact:** Semantically invalid grants that clutter state.

**Fix applied:**
```java
if (granterAddress.isZero() || granteeAddress.isZero()) {
    return FALSE;
}
```

---

## RISK-05: IndexOutOfBoundsException in NativeMinter `mint`
**Severity:** HIGH  
**Affected function:** `mint(address,uint256)`  
**Access control:** `onlyOwner` check runs first → **only owner can trigger**

Short calldata to `mint` causes `calldata.slice(12, 20)` to throw. Since `onlyOwner` is checked before slicing, only the contract owner can trigger this crash path.

**Fix needed:** Add bound check:
```java
if (calldata.size() < 64) {
    return FALSE;
}
```
