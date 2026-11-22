package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** FLPATH-5124 separate retained-name budget and snapshot semantics. */
class ServiceLoaderDescriptorRetentionSeamTest {
  @Test
  void nameBudgetIsExactAndRejectsOneOver() {
    ServiceLoaderDescriptorSource.ScanState state = ServiceLoaderDescriptorSource.ScanState
        .withBudgets(1024, 1024, 1024, 10);
    assertTrue(state.tryRetainName("a", 10));
    assertEquals(10L, state.retainedNameBytes());
    assertFalse(state.tryRetainName("b", 1));
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_retention_limit")));
  }

  @Test
  void snapshotRestoresSlotsAndNamesTogether() {
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.ScanState.RetentionSnapshot snapshot =
        state.retentionSnapshot();
    assertTrue(state.tryRetain("a"));
    assertTrue(state.tryRetainName("a", 100));
    state.restoreRetention(snapshot);
    assertEquals(0L, state.retainedBytes());
    assertEquals(0L, state.retainedNameBytes());
  }

  @Test
  void releaseReturnsBothCharges() {
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    assertTrue(state.tryRetain("a"));
    assertTrue(state.tryRetainName("a", 100));
    state.releaseRetained();
    state.releaseRetainedName(100);
    assertEquals(0L, state.retainedBytes());
    assertEquals(0L, state.retainedNameBytes());
  }
}
