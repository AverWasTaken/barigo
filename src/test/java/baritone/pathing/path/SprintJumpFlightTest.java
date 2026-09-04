/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.path;

import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SprintJumpFlightTest {

    @Test
    public void restoresInputsAndLaunchYawAfterEveryMovementUpdate() {
        Fixture f = new Fixture();
        BlockPos direction = new BlockPos(1, 0, 1);
        f.flight.start(direction, 12);
        for (int tick = 0; tick < 12; tick++) {
            f.inputs.clear();
            f.inputs.put(Input.MOVE_BACK, true); // the old movement is aiming back at its waypoint
            f.inputs.put(Input.MOVE_RIGHT, true);
            f.rotation = new Rotation(135, 0);
            assertTrue(f.flight.continueFlight(true, true, 12));
            assertEquals(Boolean.TRUE, f.inputs.get(Input.MOVE_FORWARD));
            assertEquals(Boolean.TRUE, f.inputs.get(Input.JUMP));
            assertEquals(Boolean.FALSE, f.inputs.get(Input.MOVE_BACK));
            assertEquals(Boolean.FALSE, f.inputs.get(Input.MOVE_RIGHT));
            assertEquals(-45, f.rotation.getYaw(), 1e-6);
            assertEquals(12, f.rotation.getPitch(), 1e-6);
            assertEquals(direction, f.boost);
        }
    }

    @Test
    public void landingDoesNotAutoLaunchWithoutNewRunwayCheck() {
        Fixture f = new Fixture();
        f.flight.start(new BlockPos(1, 0, 0), 0);
        f.inputs.clear();
        assertFalse(f.flight.continueFlight(false, true, 0));
        assertFalse(f.flight.active());
        assertTrue(f.inputs.isEmpty());
        f.flight.start(new BlockPos(0, 0, -1), 0); // a newly verified launch may choose a new direction
        assertTrue(f.flight.active());
        assertEquals(180, Math.abs(f.rotation.getYaw()), 1e-6);
    }

    @Test
    public void permissionLossOrInteractionRelinquishesAirControl() {
        Fixture f = new Fixture();
        f.flight.start(new BlockPos(1, 0, 0), 0);
        f.inputs.clear();
        f.inputs.put(Input.SNEAK, true);
        assertFalse(f.flight.continueFlight(true, false, 0));
        assertFalse(f.flight.active());
        assertEquals(1, f.inputs.size());
        assertEquals(Boolean.TRUE, f.inputs.get(Input.SNEAK));
        assertFalse(f.flight.continueFlight(true, true, 0));
    }

    @Test
    public void spliceTransfersFlightWithoutSharingMutableLifetime() {
        Fixture first = new Fixture();
        Fixture next = new Fixture();
        first.flight.start(new BlockPos(-1, -1, 0), 0);
        next.flight.copyFrom(first.flight);
        first.flight.clear();
        assertTrue(next.flight.continueFlight(true, true, 0));
        assertEquals(new BlockPos(-1, 0, 0), next.boost);
        assertEquals(90, next.rotation.getYaw(), 1e-6);
        next.flight.clear();
        next.inputs.clear();
        assertFalse(next.flight.continueFlight(true, true, 0));
        assertTrue(next.inputs.isEmpty());
    }

    private static class Fixture {
        final Map<Input, Boolean> inputs = new EnumMap<>(Input.class);
        Rotation rotation;
        BlockPos boost;
        final SprintJumpFlight flight = new SprintJumpFlight(inputs::put, r -> rotation = r, d -> boost = d);
    }
}
