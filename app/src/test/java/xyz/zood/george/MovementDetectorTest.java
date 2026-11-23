package xyz.zood.george;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import io.pijun.george.database.MovementType;

public class MovementDetectorTest {
    @Test public void runningPace() { assertEquals(MovementType.Running, MovementDetector.classify(2.8f, 3.2f)); }
    @Test public void walkingPace() { assertEquals(MovementType.Walking, MovementDetector.classify(1.7f, 1.4f)); }
    @Test public void walkingWithoutSpeed() { assertEquals(MovementType.Walking, MovementDetector.classify(1.5f, -1f)); }
    @Test public void driving() { assertEquals(MovementType.Vehicle, MovementDetector.classify(0f, 20f)); }
    @Test public void drivingWithoutStepCounter() { assertEquals(MovementType.Vehicle, MovementDetector.classify(null, 15f)); }
    @Test public void cycling() { assertEquals(MovementType.Bicycle, MovementDetector.classify(0f, 5f)); }
    @Test public void bikeSpeedNeedsStepCounter() { assertNull(MovementDetector.classify(null, 5f)); }
    @Test public void still() { assertEquals(MovementType.Stationary, MovementDetector.classify(0f, 0.1f)); }
    @Test public void stillWithoutStepCounter() { assertEquals(MovementType.Stationary, MovementDetector.classify(null, 0f)); }
    @Test public void unknown() { assertNull(MovementDetector.classify(null, -1f)); }
}
