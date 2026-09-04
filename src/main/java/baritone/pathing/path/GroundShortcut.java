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
import java.util.function.IntPredicate;
import java.util.function.Predicate;

final class GroundShortcut {

    private static final int MAX_MOVEMENTS = 8;
    // standing player half-width plus a little room for look quantization and sideways drift
    private static final double HALF_WIDTH = 0.35;
    private static final double MAX_PATH_DEVIATION = 1.0;
    private static final double COAST_TICKS = 2.5;

    final int endIndex;
    final Vec3 start;
    final Vec3 target;

    private GroundShortcut(int endIndex, Vec3 start, Vec3 target) {
        this.endIndex = endIndex;
        this.start = start;
        this.target = target;
    }

    static GroundShortcut find(List<BetterBlockPos> path, int index, Vec3 player,
                               IntPredicate plainMovement, Predicate<BlockPos> clearColumn) {
        if (index < 0 || index + 2 >= path.size()) {
            return null;
        }
        Vec3 source = center(path.get(index));
        Vec3 first = center(path.get(index + 1));
        if (Math.abs(player.y - source.y) > 0.05 || distanceToSegment(player, source, first) > 0.4) {
            return null; // recover from knockback using the original movement
        }
        int end = index;
        for (int i = index; i < Math.min(index + MAX_MOVEMENTS, path.size() - 1); i++) {
            BlockPos step = path.get(i + 1).subtract(path.get(i));
            if (step.getY() != 0 || Math.abs(step.getX()) > 1 || Math.abs(step.getZ()) > 1
                    || step.getX() == 0 && step.getZ() == 0 || !plainMovement.test(i)) {
                break;
            }
            end = i + 1;
        }
        for (int i = end; i >= index + 2; i--) {
            Vec3 target = center(path.get(i));
            if (usefulShortcut(path, index, i, player, target)
                    && clearSegment(player, target, clearColumn)) {
                return new GroundShortcut(i, player, target);
            }
        }
        return null;
    }

    private static boolean usefulShortcut(List<BetterBlockPos> path, int index, int end, Vec3 player, Vec3 target) {
        double direct = player.distanceTo(target);
        double original = player.distanceTo(center(path.get(index + 1)));
        double previousProgress = 0;
        Vec3 direction = target.subtract(player);
        for (int i = index + 1; i <= end; i++) {
            Vec3 node = center(path.get(i));
            double progress = node.subtract(player).dot(direction);
            if (progress < previousProgress || distanceToSegment(node, player, target) > MAX_PATH_DEVIATION) {
                return false; // no U-turns or long cuts across the inside of a detour
            }
            previousProgress = progress;
            if (i < end) {
                original += node.distanceTo(center(path.get(i + 1)));
            }
        }
        // straight runs already have smoothing and sprint jumps. only take over for an actual bend.
        return direct >= 1.5 && original - direct > 0.1;
    }

    boolean canContinue(Vec3 player, Vec3 velocity, Predicate<BlockPos> clearColumn) {
        return Math.abs(player.y - target.y) <= 0.05
                && distanceToSegment(player, start, target) <= 0.6
                && clearSegment(player, target, clearColumn)
                && clearSegment(player, coastPosition(player, velocity), clearColumn);
    }

    boolean arrived(Vec3 player, Vec3 velocity) {
        Vec3 coast = coastPosition(player, velocity);
        return player.distanceTo(target) <= 0.3
                && Math.abs(coast.x - target.x) < 0.4 && Math.abs(coast.z - target.z) < 0.4;
    }

    boolean shouldMoveForward(Vec3 player, Vec3 velocity) {
        Vec3 remaining = target.subtract(player);
        // releasing W early lets ground friction catch us before a turn, goal, or precise movement.
        return remaining.lengthSqr() > 0.04
                && remaining.dot(velocity.scale(COAST_TICKS)) < remaining.lengthSqr();
    }

    private static Vec3 coastPosition(Vec3 player, Vec3 velocity) {
        return player.add(velocity.x * COAST_TICKS, 0, velocity.z * COAST_TICKS);
    }

    // exact segment-versus-expanded-cell intersections, not a ray or samples that can miss a corner.
    // every cell touched by the player's swept footprint must have both clearance and full support.
    static boolean clearSegment(Vec3 from, Vec3 to, Predicate<BlockPos> clearColumn) {
        if (Math.abs(from.y - to.y) > 0.05 || from.distanceToSqr(to) > 256) {
            return false;
        }
        int y = (int) Math.floor(from.y + 0.05);
        for (int x = (int) Math.floor(Math.min(from.x, to.x) - HALF_WIDTH);
             x <= (int) Math.floor(Math.max(from.x, to.x) + HALF_WIDTH); x++) {
            for (int z = (int) Math.floor(Math.min(from.z, to.z) - HALF_WIDTH);
                 z <= (int) Math.floor(Math.max(from.z, to.z) + HALF_WIDTH); z++) {
                if (intersectsCell(from, to, x, z) && !clearColumn.test(new BlockPos(x, y, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean intersectsCell(Vec3 from, Vec3 to, int x, int z) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double minX = x - HALF_WIDTH;
        double maxX = x + 1 + HALF_WIDTH;
        double minZ = z - HALF_WIDTH;
        double maxZ = z + 1 + HALF_WIDTH;
        if (dx == 0 && (from.x < minX || from.x > maxX)
                || dz == 0 && (from.z < minZ || from.z > maxZ)) {
            return false;
        }
        double enterX = dx == 0 ? Double.NEGATIVE_INFINITY : Math.min((minX - from.x) / dx, (maxX - from.x) / dx);
        double exitX = dx == 0 ? Double.POSITIVE_INFINITY : Math.max((minX - from.x) / dx, (maxX - from.x) / dx);
        double enterZ = dz == 0 ? Double.NEGATIVE_INFINITY : Math.min((minZ - from.z) / dz, (maxZ - from.z) / dz);
        double exitZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.max((minZ - from.z) / dz, (maxZ - from.z) / dz);
        return Math.max(0, Math.max(enterX, enterZ)) <= Math.min(1, Math.min(exitX, exitZ));
    }

    private static double distanceToSegment(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.lengthSqr();
        double progress = length == 0 ? 0 : Math.max(0, Math.min(1, point.subtract(from).dot(delta) / length));
        return point.distanceTo(from.add(delta.scale(progress)));
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
