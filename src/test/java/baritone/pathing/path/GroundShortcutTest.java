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

import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class GroundShortcutTest {

    @Test
    public void cutsStaircaseIntoDirectLineInEveryOctant() {
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                for (boolean swap : new boolean[]{false, true}) {
                    List<BetterBlockPos> path = staircase(sx, sz, swap, 8);
                    GroundShortcut shortcut = find(path);
                    assertNotNull(shortcut);
                    assertEquals(8, shortcut.endIndex);
                    assertEquals(center(path.get(8)), shortcut.target);
                    assertTrue(shortcut.start.distanceTo(shortcut.target) < 4 + 4 * Math.sqrt(2));
                }
            }
        }
    }

    @Test
    public void leavesStraightRunsForSprintJumpingAndExistingSmoothing() {
        assertNull(find(path(0, 0, 1, 0, 2, 0, 3, 0)));
        assertNull(find(path(0, 0, 1, 1, 2, 2, 3, 3)));
    }

    @Test
    public void canRoundOpenCornerButCannotCutThroughItsWall() {
        List<BetterBlockPos> path = path(0, 0, 1, 0, 2, 0, 2, 1, 2, 2);
        assertNotNull(find(path));
        // the shortcut's center ray misses this block on some candidates, its body doesn't
        assertNull(GroundShortcut.find(path, 0, center(path.get(0)), i -> true,
                p -> !p.equals(new BlockPos(1, 64, 1))));
    }

    @Test
    public void stopsBeforeSpecialMovementsAndHeightChanges() {
        List<BetterBlockPos> path = staircase(1, 1, false, 8);
        assertNull(GroundShortcut.find(path, 0, center(path.get(0)), i -> i != 1, p -> true));
        GroundShortcut shortRun = GroundShortcut.find(path, 0, center(path.get(0)), i -> i < 4, p -> true);
        assertEquals(4, shortRun.endIndex);
        for (int height : new int[]{63, 65, 68}) {
            List<BetterBlockPos> changed = new ArrayList<>(path);
            changed.set(2, new BetterBlockPos(2, height, 1));
            assertNull(find(changed));
        }
    }

    @Test
    public void doesNotSkipReversalsOrLargeDetours() {
        assertNull(find(path(0, 0, 1, 0, 0, 0, -1, 0)));
        List<BetterBlockPos> detour = path(0, 0, 1, 0, 2, 0, 3, 0, 3, 1, 3, 2, 3, 3);
        GroundShortcut shortcut = find(detour);
        assertTrue(shortcut == null || shortcut.endIndex < 6);
    }

    @Test
    public void rejectsRecoveryPositionsAndShortPaths() {
        List<BetterBlockPos> path = staircase(1, 1, false, 8);
        for (Vec3 player : Arrays.asList(new Vec3(0.5, 64.2, 0.5), new Vec3(0.5, 64, 1.2),
                new Vec3(-1, 64, 0.5), new Vec3(3, 64, 0.5))) {
            assertNull(GroundShortcut.find(path, 0, player, i -> true, p -> true));
        }
        assertNull(find(path.subList(0, 2)));
        assertNull(GroundShortcut.find(path, -1, center(path.get(0)), i -> true, p -> true));
        assertNull(GroundShortcut.find(path, path.size() - 1, center(path.get(8)), i -> true, p -> true));
    }

    @Test
    public void boundsLookaheadEvenOnVeryLongPaths() {
        List<BetterBlockPos> path = staircase(1, 1, false, 1000);
        GroundShortcut shortcut = GroundShortcut.find(path, 0, center(path.get(0)), i -> {
            assertTrue(i < 8);
            return true;
        }, p -> {
            assertTrue(p.getX() <= 8);
            return true;
        });
        assertEquals(8, shortcut.endIndex);
    }

    @Test
    public void footprintChecksBothSidesOfDiagonalAndNegativeCoordinates() {
        for (int offset : new int[]{0, -10}) {
            Set<BlockPos> checked = new HashSet<>();
            Vec3 from = new Vec3(offset + 0.5, 64, offset + 0.5);
            Vec3 to = new Vec3(offset + 2.5, 64, offset + 2.5);
            assertTrue(GroundShortcut.clearSegment(from, to, p -> {
                checked.add(p);
                return true;
            }));
            for (BlockPos side : Arrays.asList(new BlockPos(offset + 1, 64, offset),
                    new BlockPos(offset, 64, offset + 1))) {
                assertTrue(checked.contains(side));
                assertFalse(GroundShortcut.clearSegment(from, to, p -> !p.equals(side)));
            }
            assertFalse(checked.contains(new BlockPos(offset + 2, 64, offset)));
        }
    }

    @Test
    public void footprintFitsOneBlockCorridorButRejectsOverhangingItsFloor() {
        assertTrue(GroundShortcut.clearSegment(new Vec3(0.5, 64, 0.5), new Vec3(6.5, 64, 0.5),
                p -> p.getZ() == 0));
        assertFalse(GroundShortcut.clearSegment(new Vec3(0.5, 64, 0.8), new Vec3(6.5, 64, 0.8),
                p -> p.getZ() == 0));
        assertFalse(GroundShortcut.clearSegment(new Vec3(0.5, 64, 0.5), new Vec3(6.5, 64, 0.5),
                p -> p.getX() != 3));
    }

    @Test
    public void rechecksRouteAndMomentumWithoutChangingEndpoint() {
        GroundShortcut shortcut = find(staircase(1, 1, false, 8));
        Vec3 player = new Vec3(2.5, 64, 1.5);
        Vec3 target = shortcut.target;
        assertTrue(shortcut.canContinue(player, Vec3.ZERO, p -> true));
        assertFalse(shortcut.canContinue(player, Vec3.ZERO, p -> p.getX() != 6));
        assertTrue(shortcut.canContinue(player, Vec3.ZERO, p -> p.getX() != 0)); // changes behind us don't matter
        assertFalse(shortcut.canContinue(player, new Vec3(0, 0, -1), p -> p.getZ() >= 0));
        assertFalse(shortcut.canContinue(player.add(0, 1, 0), Vec3.ZERO, p -> true));
        assertFalse(shortcut.canContinue(player.add(0, 0, 2), Vec3.ZERO, p -> true));
        assertEquals(target, shortcut.target);
    }

    @Test
    public void brakesBeforeEndpointAndDoesNotFinishWithDangerousMomentum() {
        GroundShortcut shortcut = find(staircase(1, 1, false, 8));
        Vec3 approaching = shortcut.target.add(-0.25, 0, 0);
        assertTrue(shortcut.shouldMoveForward(approaching, Vec3.ZERO));
        assertFalse(shortcut.shouldMoveForward(approaching, new Vec3(0.2, 0, 0)));
        assertFalse(shortcut.arrived(approaching, new Vec3(0.4, 0, 0)));
        assertTrue(shortcut.arrived(approaching, new Vec3(0.1, 0, 0)));
        assertTrue(shortcut.arrived(shortcut.target, Vec3.ZERO));
        assertFalse(shortcut.shouldMoveForward(shortcut.target, Vec3.ZERO));
    }

    @Test
    public void controllerReachesEndpointWithGroundInertiaAndSmallSteeringError() {
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                GroundShortcut shortcut = find(staircase(sx, sz, false, 8));
                Vec3 player = shortcut.start;
                Vec3 velocity = Vec3.ZERO;
                int ticks = 0;
                // simplified ground motion fixture: acceleration before movement, friction afterward.
                // this checks controller convergence; it is not a Minecraft travel-speed benchmark.
                while (!shortcut.arrived(player, velocity) && ticks++ < 100) {
                    assertTrue(shortcut.canContinue(player, velocity, p -> true));
                    if (shortcut.shouldMoveForward(player, velocity)) {
                        double accel = player.distanceTo(shortcut.target) > 1.5 ? 0.1274 : 0.098;
                        Vec3 thrust = shortcut.target.subtract(player).normalize().yRot((float) Math.toRadians(ticks % 2 == 0 ? 2 : -2));
                        velocity = velocity.add(thrust.scale(accel));
                    }
                    player = player.add(velocity);
                    velocity = velocity.scale(0.546);
                }
                assertTrue("controller failed to settle", ticks < 100);
                assertTrue(player.distanceTo(shortcut.target) <= 0.3);
            }
        }
    }

    @Test
    public void segmentClearanceIsSymmetricAndIncludesStationaryFootprint() {
        Vec3 from = new Vec3(-1.2, 64, 0.7);
        Vec3 to = new Vec3(3.1, 64, 2.2);
        Set<BlockPos> forward = new HashSet<>();
        Set<BlockPos> backward = new HashSet<>();
        assertTrue(GroundShortcut.clearSegment(from, to, p -> { forward.add(p); return true; }));
        assertTrue(GroundShortcut.clearSegment(to, from, p -> { backward.add(p); return true; }));
        assertEquals(forward, backward);
        assertFalse(GroundShortcut.clearSegment(from, from, p -> !p.equals(new BlockPos(-2, 64, 0))));
    }

    private static GroundShortcut find(List<BetterBlockPos> path) {
        return GroundShortcut.find(path, 0, center(path.get(0)), i -> true, p -> true);
    }

    private static List<BetterBlockPos> staircase(int sx, int sz, boolean swap, int length) {
        List<BetterBlockPos> result = new ArrayList<>();
        for (int i = 0; i <= length; i++) {
            result.add(new BetterBlockPos(sx * (swap ? i / 2 : i), 64, sz * (swap ? i : i / 2)));
        }
        return result;
    }

    private static List<BetterBlockPos> path(int... xz) {
        List<BetterBlockPos> result = new ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) {
            result.add(new BetterBlockPos(xz[i], 64, xz[i + 1]));
        }
        return result;
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
