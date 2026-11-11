package dev.raskol.vault.reserve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReserveCrisisPolicyTest {

    @Test
    void entersWarningBelowRecoveryThreshold() {
        assertEquals(ReserveCrisisPolicy.Level.WARNING,
                ReserveCrisisPolicy.next(ReserveCrisisPolicy.Level.NORMAL, 0.55D, 0.50D, 0.60D));
    }

    @Test
    void entersCrisisBelowFloor() {
        assertEquals(ReserveCrisisPolicy.Level.CRISIS,
                ReserveCrisisPolicy.next(ReserveCrisisPolicy.Level.WARNING, 0.49D, 0.50D, 0.60D));
    }

    @Test
    void crisisPersistsUntilRecoveryThreshold() {
        assertEquals(ReserveCrisisPolicy.Level.CRISIS,
                ReserveCrisisPolicy.next(ReserveCrisisPolicy.Level.CRISIS, 0.55D, 0.50D, 0.60D));
    }

    @Test
    void crisisRecoversAtSixtyPercent() {
        assertEquals(ReserveCrisisPolicy.Level.NORMAL,
                ReserveCrisisPolicy.next(ReserveCrisisPolicy.Level.CRISIS, 0.60D, 0.50D, 0.60D));
    }

    @Test
    void invalidCoverageFailsClosed() {
        assertEquals(ReserveCrisisPolicy.Level.CRISIS,
                ReserveCrisisPolicy.next(ReserveCrisisPolicy.Level.NORMAL, Double.NaN, 0.50D, 0.60D));
    }
}
