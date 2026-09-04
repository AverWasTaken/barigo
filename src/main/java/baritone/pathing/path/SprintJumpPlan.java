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

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;

final class SprintJumpPlan {

    private static final int MAX_MOVEMENTS = 12;
    final BlockPos direction;
    final Vec3 runwayEnd;

    private SprintJumpPlan(BlockPos direction, Vec3 runwayEnd) {
        this.direction = direction;
        this.runwayEnd = runwayEnd;
    }

    // the column predicate checks support and body clearance up to the supplied absolute top Y.
    // ceiling hops use two clear blocks plus a full ceiling; open hops need FOUR clear blocks.
    static SprintJumpPlan find(List<BetterBlockPos> path, int index, Vec3 player, Vec3 velocity,
                               double acceleration, boolean ceiling, boolean downhill,
                               IntPredicate eligible, BiPredicate<BlockPos, Integer> clearColumn) {
        if (index < 0 || index + 1 >= path.size()) {
            return null;
        }
        BetterBlockPos source = path.get(index);
        BlockPos first = path.get(index + 1).subtract(source);
        BlockPos direction = new BlockPos(first.getX(), 0, first.getZ());
        double length = Math.hypot(direction.getX(), direction.getZ());
        if (length == 0 || Math.abs(first.getX()) > 1 || Math.abs(first.getZ()) > 1
                || Math.abs(player.y - source.y) > 0.05 || !aligned(source, direction, player, velocity)) {
            return null;
        }
        double speed = (velocity.x * direction.getX() + velocity.z * direction.getZ()) / length;
        if (!Double.isFinite(speed) || speed < 0 || !Double.isFinite(acceleration) || acceleration <= 0 || acceleration > 0.5) {
            return null;
        }
        double progress = ((player.x - source.x - 0.5) * direction.getX()
                + (player.z - source.z - 0.5) * direction.getZ()) / length;
        if (progress < -0.5 || progress > length + 0.5) {
            return null;
        }
        for (int i = index; i < Math.min(index + MAX_MOVEMENTS, path.size() - 1); i++) {
            BetterBlockPos dest = path.get(i + 1);
            BlockPos step = dest.subtract(path.get(i));
            int drop = source.y - dest.y;
            if (!eligible.test(i) || step.getX() != direction.getX() || step.getZ() != direction.getZ()
                    || step.getY() > 0 || drop > 1 || drop < 0
                    || step.getY() < 0 && (!downhill || ceiling || length > 1)) {
                return null;
            }
            double available = (i + 1 - index) * length - progress;
            // include a possible one-block drop in the flight budget before looking beyond this node.
            // if the next step drops, the player may still be airborne instead of landing here.
            double required = requiredRunway(speed, acceleration, ceiling, downhill && !ceiling ? 1 : 0);
            if (available < required) {
                continue;
            }
            Vec3 end = new Vec3(dest.x + 0.5, player.y, dest.z + 0.5);
            int last = i + 1;
            int topY = source.y + (ceiling ? 1 : 3);
            boolean clear = GroundShortcut.clearSegment(player, end, 0.45, column -> {
                int floorY = source.y;
                if (length == 1) {
                    int offset = (column.getX() - source.x) * direction.getX()
                            + (column.getZ() - source.z) * direction.getZ();
                    floorY = path.get(Math.max(index, Math.min(last, index + offset))).y;
                }
                return clearColumn.test(new BlockPos(column.getX(), floorY, column.getZ()), topY);
            });
            return clear ? new SprintJumpPlan(direction, end) : null;
        }
        return null;
    }

    static boolean aligned(BlockPos source, BlockPos direction, Vec3 player, Vec3 velocity) {
        double length = Math.hypot(direction.getX(), direction.getZ());
        if (length == 0) {
            return false;
        }
        double offset = ((player.x - source.getX() - 0.5) * direction.getZ()
                - (player.z - source.getZ() - 0.5) * direction.getX()) / length;
        double sideways = (velocity.x * direction.getZ() - velocity.z * direction.getX()) / length;
        // the old 0.1 velocity limit let a whole block of sideways drift through on a diagonal.
        return Math.abs(offset) <= 0.12 && Math.abs(sideways) <= 0.03
                && Math.abs(offset + sideways * 10) <= 0.15;
    }

    static double requiredRunway(double speed, double acceleration, boolean ceiling, int drop) {
        // 1.19.4 LivingEntity: sprint boost, first-tick ground friction, then air acceleration/drag.
        // vertical clipping models a full two-high ceiling. add coasting room after touchdown so
        // the last hop cannot carry us straight through a turn or off the end of the path.
        double horizontal = Math.max(0, speed) + 0.2;
        double vertical = 0.42;
        double height = 0;
        double distance = 0;
        for (int tick = 0; tick < 30; tick++) {
            horizontal += tick == 0 ? acceleration : 0.026;
            distance += horizontal;
            height += vertical;
            if (ceiling && height > 0.2) {
                height = 0.2;
                vertical = 0;
            }
            horizontal *= tick == 0 ? 0.546 : 0.91;
            if (height <= -drop && vertical < 0) {
                break;
            }
            vertical = (vertical - 0.08) * 0.98;
        }
        return distance + horizontal * 2.5 + 0.5;
    }
}
