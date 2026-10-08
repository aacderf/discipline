package com.hl.discipline;

import static org.junit.Assert.*;
import static com.hl.discipline.LocationQuality.Decision.*;
import org.junit.Test;

public class LocationQualityTest {
  @Test public void recentPreciseFixSurvivesCoarseProviderAndStaleResults() {
    LocationQuality quality = new LocationQuality();
    assertEquals(ACCEPT, quality.consider(100_000, 100_000, 31, 121, 8));
    assertEquals(IGNORE, quality.consider(102_000, 102_000, 31.01, 121.01, 800));
    assertEquals(IGNORE, quality.consider(125_000, 101_000, 31, 121, 8));
    assertEquals(ACCEPT, quality.consider(110_000, 110_000, 31, 121, 10));
  }

  @Test public void isolatedImpossibleJumpIsQuarantinedButConfirmedMovementIsAllowed() {
    LocationQuality quality = new LocationQuality();
    assertEquals(ACCEPT, quality.consider(100_000, 100_000, 31, 121, 8));
    assertEquals(SUSPECT, quality.consider(101_000, 101_000, 31.02, 121, 8));
    assertEquals(SUSPECT, quality.consider(101_000, 101_000, 31.02, 121, 8));
    assertEquals(ACCEPT, quality.consider(102_000, 102_000, 31, 121, 8));
    assertEquals(SUSPECT, quality.consider(103_000, 103_000, 31.02, 121, 8));
    assertEquals(ACCEPT, quality.consider(105_000, 105_000, 31.02001, 121, 8));
  }

  @Test public void expiredBetterFixDoesNotPinTheUserToTheOldPlace() {
    LocationQuality quality = new LocationQuality();
    quality.consider(100_000, 100_000, 31, 121, 8);
    assertEquals(ACCEPT, quality.consider(121_000, 121_000, 31.001, 121, 300));
    assertEquals(IGNORE, quality.consider(122_000, 122_000, Double.NaN, 121, 8));
  }
}
