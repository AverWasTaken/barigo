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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.Assert.*;

public class SprintJumpPlanTest {

    @Test
    public void findsSupportedRunwayInAllEightDirections() {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                List<BetterBlockPos> path = line(dx, dz, 12);
                SprintJumpPlan plan = find(path, 0, new Vec3(0.5, 64, 0.5), velocity(dx, dz, 0.16), false, false, (p, top) -> true);
                assertNotNull(plan);
                assertEquals(new BlockPos(dx, 0, dz), plan.direction);
                assertTrue(plan.runwayEnd.distanceTo(new Vec3(0.5, 64, 0.5)) >= SprintJumpPlan.requiredRunway(0.16, 0.13, false, 0));
            }
        }
    }

    @Test
    public void fasterMotionAndDownhillRequireMoreRunway() {
        double normal = SprintJumpPlan.requiredRunway(0.16, 0.13, false, 0);
        assertTrue(SprintJumpPlan.requiredRunway(0.5, 0.13, false, 0) > normal);
        assertTrue(SprintJumpPlan.requiredRunway(0.16, 0.26, false, 0) > normal);
        assertTrue(SprintJumpPlan.requiredRunway(0.16, 0.13, false, 1) > normal);
        assertTrue(SprintJumpPlan.requiredRunway(0.16, 0.13, true, 0) < normal);
        assertNotNull(find(line(1, 0, 6), 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), false, false, (p, t) -> true));
        assertNull(find(line(1, 0, 6), 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.8), false, false, (p, t) -> true));
    }

    @Test
    public void measuresRunwayFromPlayerInsteadOfLaggingMovementIndex() {
        double required = SprintJumpPlan.requiredRunway(0.16, 0.13, false, 0);
        int end = (int) Math.ceil(required);
        List<BetterBlockPos> path = line(1, 0, end);
        assertNotNull(find(path, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), false, false, (p, t) -> true));
        assertNull(find(path, 0, new Vec3(1.5, 64, 0.5), velocity(1, 0, 0.16), false, false, (p, t) -> true));
    }

    @Test
    public void stopsBeforeTurnsGoalsAndInteractionsEvenForHeadhitters() {
        List<BetterBlockPos> path = line(1, 0, 10);
        assertNull(find(path.subList(0, 2), 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), true, false, (p, t) -> true));
        path.set(2, new BetterBlockPos(1, 64, 1));
        assertNull(find(path, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), true, false, (p, t) -> true));
        path = line(1, 0, 10);
        assertNull(SprintJumpPlan.find(path, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), 0.13,
                false, false, i -> i != 2, (p, t) -> true));
    }

    @Test
    public void openJumpChecksFourthBlockOfHeadroomAndDiagonalCorners() {
        Set<BlockPos> columns = new HashSet<>();
        List<BetterBlockPos> path = line(1, 1, 10);
        assertNotNull(find(path, 0, new Vec3(0.5, 64, 0.5), velocity(1, 1, 0.16), false, false, (p, top) -> {
            assertEquals(67, top.intValue());
            columns.add(p);
            return true;
        }));
        assertTrue(columns.contains(new BlockPos(1, 64, 0)));
        assertTrue(columns.contains(new BlockPos(0, 64, 1)));
        for (BlockPos blocked : columns) {
            assertNull(find(path, 0, new Vec3(0.5, 64, 0.5), velocity(1, 1, 0.16), false, false,
                    (p, top) -> !p.equals(blocked)));
        }
    }

    @Test
    public void ceilingMustCoverWholeFootprintIncludingEntranceAndExit() {
        List<BetterBlockPos> path = line(1, 0, 10);
        assertNotNull(find(path, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), true, false, (p, top) -> top == 65));
        assertNull(find(path, 0, new Vec3(0.2, 64, 0.5), velocity(1, 0, 0.16), true, false,
                (p, top) -> p.getX() >= 0));
        assertNull(find(path, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), true, false,
                (p, top) -> p.getX() < 2));
    }

    @Test
    public void onlyAllowsOneBlockOfTotalDownhillDrop() {
        List<BetterBlockPos> one = line(1, 0, 12);
        for (int i = 1; i < one.size(); i++) one.set(i, one.get(i).below());
        Set<Integer> floors = new HashSet<>();
        assertNotNull(find(one, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), false, true, (p, top) -> {
            floors.add(p.getY());
            assertEquals(67, top.intValue());
            return true;
        }));
        assertTrue(floors.contains(63));
        assertTrue(floors.contains(64));
        assertNull(find(one, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), false, false, (p, t) -> true));
        assertNull(find(one, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), true, true, (p, t) -> true));
        for (int i = 2; i < one.size(); i++) one.set(i, one.get(i).below());
        assertNull(find(one, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), false, true, (p, t) -> true));
    }

    @Test
    public void rejectsAscentsTwoBlockDropsAndDiagonalDescents() {
        for (int change : new int[]{1, -2}) {
            List<BetterBlockPos> path = line(1, 0, 12);
            path.set(1, new BetterBlockPos(1, 64 + change, 0));
            assertNull(find(path, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), false, true, (p, t) -> true));
        }
        List<BetterBlockPos> diagonal = line(1, 1, 12);
        diagonal.set(1, diagonal.get(1).below());
        assertNull(find(diagonal, 0, new Vec3(0.5, 64, 0.5), velocity(1, 1, 0.16), false, true, (p, t) -> true));
    }

    @Test
    public void lateralMomentumHasSameLimitOnCardinalsAndDiagonals() {
        BlockPos source = new BlockPos(0, 64, 0);
        Vec3 player = new Vec3(0.5, 64, 0.5);
        for (int dz : new int[]{0, 1}) {
            BlockPos direction = new BlockPos(1, 0, dz);
            Vec3 forward = velocity(1, dz, 0.16);
            Vec3 sideways = velocity(-dz, 1, 0.02);
            assertTrue(SprintJumpPlan.aligned(source, direction, player, forward));
            assertFalse(SprintJumpPlan.aligned(source, direction, player, forward.add(sideways)));
            assertFalse(SprintJumpPlan.aligned(source, direction, player.add(sideways.scale(10)), forward));
        }
    }

    @Test
    public void rechecksChangedTerrainBeforeNextLaunch() {
        List<BetterBlockPos> path = line(1, 0, 16);
        Set<Integer> holes = new HashSet<>();
        BiPredicate<BlockPos, Integer> world = (p, top) -> !holes.contains(p.getX());
        assertNotNull(find(path, 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 0.16), false, false, world));
        holes.add(7);
        assertNull(find(path, 4, new Vec3(4.5, 64, 0.5), velocity(1, 0, 0.3), false, false, world));
        holes.clear();
        assertNotNull(find(path, 4, new Vec3(4.5, 64, 0.5), velocity(1, 0, 0.3), false, false, world));
    }

    @Test
    public void boundsWorkForExtremeSpeedAndLongPaths() {
        assertNull(SprintJumpPlan.find(line(1, 0, 1000), 0, new Vec3(0.5, 64, 0.5), velocity(1, 0, 3),
                0.13, false, false, i -> { assertTrue(i < 12); return true; }, (p, t) -> true));
    }

    @Test
    public void rejectsOffGroundAndInvalidMotion() {
        List<BetterBlockPos> path = line(1, 0, 12);
        assertNull(find(path, 0, new Vec3(0.5, 64.2, 0.5), velocity(1, 0, 0.16), false, false, (p, t) -> true));
        assertNull(find(path, 0, new Vec3(0.5, 64, 0.5), velocity(-1, 0, 0.16), false, false, (p, t) -> true));
        assertNull(find(path, 0, new Vec3(0.5, 64, 0.5), new Vec3(Double.NaN, 0, 0), false, false, (p, t) -> true));
    }

    private static SprintJumpPlan find(List<BetterBlockPos> path, int index, Vec3 player, Vec3 velocity,
                                       boolean ceiling, boolean downhill, BiPredicate<BlockPos, Integer> world) {
        return SprintJumpPlan.find(path, index, player, velocity, 0.13, ceiling, downhill, i -> true, world);
    }

    private static List<BetterBlockPos> line(int dx, int dz, int length) {
        List<BetterBlockPos> path = new ArrayList<>();
        for (int i = 0; i <= length; i++) path.add(new BetterBlockPos(i * dx, 64, i * dz));
        return path;
    }

    private static Vec3 velocity(int dx, int dz, double speed) {
        return new Vec3(dx, 0, dz).normalize().scale(speed);
    }
}
