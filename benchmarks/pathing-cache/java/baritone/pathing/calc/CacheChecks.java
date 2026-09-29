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

package baritone.pathing.calc;

import baritone.Baritone;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class CacheChecks {
    static final class World extends WalkingBench.World {
        volatile boolean stone;
        World() { super("flat"); }
        @Override protected BlockState getUncached(int x, int y, int z) {
            if (stone && x == 8 && y + minY == 64 && z == 0) return Blocks.STONE.defaultBlockState();
            return super.getUncached(x, y, z);
        }
    }
    static long[] keys(CalculationContext context) throws Exception {
        Field field = CalculationContext.class.getDeclaredField("miningKeys");
        field.setAccessible(true);
        return (long[]) field.get(context);
    }
    static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Baritone.settings().considerPotionEffects.value = false;
        Baritone.settings().allowBreak.value = true;
        for (int size : new int[]{Integer.MIN_VALUE, 1025, 16384, Integer.MAX_VALUE}) {
            WalkingBench.configureSize(size);
            World world = new World();
            WalkingBench.Context outer = new WalkingBench.Context(world);
            WalkingBench.configureSize(65536);
            outer.claimSearchCaches();
            long[] first = keys(outer);
            int expectedSize = size < 1024 ? 1024 : size > 65536 ? 65536 : size == 1025 ? 2048 : size;
            check(first.length == expectedSize, "context did not capture normalized size");
            try {
                for (boolean falling : new boolean[]{false, true}) {
                    check(MovementHelper.getMiningDurationTicks(outer, 8, 64, 0, falling) == 0, "air cost");
                }
                world.stone = true;
                FutureTask<Void> other = new FutureTask<>(() -> {
                    outer.claimSearchCaches();
                    check(outer.get(8, 64, 0).getBlock() == Blocks.STONE, "foreign block lookup used owner cache");
                    check(MovementHelper.getMiningDurationTicks(outer, 8, 64, 0, false) > 0, "foreign mining lookup used owner cache");
                    outer.releaseSearchCaches();
                    return null;
                });
                new Thread(other).start(); other.get(10, TimeUnit.SECONDS);
                check(keys(outer) == first, "foreign release detached arrays");
                check(outer.get(8, 64, 0).getBlock() == Blocks.AIR, "foreign lookup modified owner's block cache");
                World innerWorld = new World(); innerWorld.stone = true;
                WalkingBench.Context inner = new WalkingBench.Context(innerWorld);
                inner.claimSearchCaches();
                try {
                    check(keys(inner) != first, "nested search shares arrays");
                    check(inner.get(8, 64, 0).getBlock() == Blocks.STONE, "nested search saw outer snapshot");
                    check(outer.get(8, 64, 0).getBlock() == Blocks.AIR, "nested search changed outer snapshot");
                } finally { inner.releaseSearchCaches(); }
            } finally { outer.releaseSearchCaches(); }
            check(keys(outer) == null, "release retained context arrays");
            for (int i = 0; i < 100; i++) {
                world.stone = (i & 1) == 0;
                outer.claimSearchCaches();
                try {
                    check(keys(outer) == first, "released outer buffer was evicted");
                    for (boolean falling : new boolean[]{false, true}) {
                        double cost = MovementHelper.getMiningDurationTicks(outer, 8, 64, 0, falling);
                        check(world.stone ? cost > 0 : cost == 0, "stale mining or block data after reuse");
                    }
                } finally { outer.releaseSearchCaches(); }
            }
        }
        System.out.println("cache ownership, nested claims, capacity snapshot and stale-value checks passed");
    }
}
