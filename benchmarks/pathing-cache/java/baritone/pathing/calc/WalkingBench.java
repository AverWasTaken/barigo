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
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.*;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import java.lang.management.ManagementFactory;
import java.util.Locale;

public final class WalkingBench {
    static final com.sun.management.ThreadMXBean ALLOC = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static volatile long blackhole;
    static long lastMisses;

    static class World extends BlockStateInterface {
        final String kind;
        long misses;
        World(String kind) { super(new BetterWorldBorder(new WorldBorder()), -64, 384); this.kind = kind; }
        int floor(int x, int z) {
            int step = Math.floorMod(Math.floorDiv(x, 12), 8);
            return kind.equals("terraces") ? 64 + Math.min(step, 8 - step) : 64;
        }
        @Override protected BlockState getUncached(int x, int shiftedY, int z) {
            misses++;
            int y = shiftedY + minY;
            if (y < floor(x, z)) return Blocks.BEDROCK.defaultBlockState();
            if (kind.equals("obstacles") && y < 68 && Math.floorMod(x, 24) == 12 && Math.floorMod(z, 80) < 60) return Blocks.BEDROCK.defaultBlockState();
            if (kind.equals("mining") && y < 67 && Math.floorMod(x, 16) == 8) return Blocks.STONE.defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }
        @Override public boolean isLoaded(int x, int z) { return Math.abs(x) < 4096 && Math.abs(z) < 4096; }
        @Override public boolean worldContainsLoadedChunk(int x, int z) { return isLoaded(x, z); }
    }
    static final class Context extends CalculationContext {
        Context(World world) {
            super(null, true, null, null, world, new ToolSet(null) {
                @Override public double getStrVsBlock(BlockState state) { return 0.2; }
            }, false, false, true, 0, 0);
        }
    }
    static String run(String kind) {
        Baritone.settings().allowBreak.value = kind.equals("mining");
        World world = new World(kind);
        Context ctx = new Context(world);
        int gx = kind.equals("short") ? 12 : kind.equals("obstacles") ? 240 : kind.equals("mining") ? 96 : 300;
        int gz = kind.equals("obstacles") ? 160 : kind.equals("short") ? 8 : kind.equals("mining") ? 32 : 100;
        Goal goal = new GoalBlock(gx, world.floor(gx, gz), gz);
        if (kind.equals("composite")) {
            Goal[] goals = new Goal[128];
            for (int i = 0; i < goals.length; i++) goals[i] = new GoalBlock(gx + i * 2, 64, gz + i * 3);
            goal = new GoalComposite(goals);
        }
        AStarPathFinder finder = new AStarPathFinder(new BetterBlockPos(0, 64, 0), 0, 64, 0, goal, new Favoring(null, ctx), ctx);
        ctx.claimSearchCaches();
        IPath path;
        try { path = finder.calculate0(60000, 60000).orElseThrow(); }
        finally { ctx.releaseSearchCaches(); }
        if (!goal.isInGoal(path.getDest())) throw new AssertionError("did not reach goal: " + kind);
        long hash = 1;
        for (BetterBlockPos pos : path.positions()) hash = 31 * hash + BetterBlockPos.longHash(pos.x, pos.y, pos.z);
        PathNode end = finder.mostRecentConsidered;
        blackhole = hash;
        lastMisses = world.misses;
        return finder.numNodesConsidered + "," + finder.numMovementsConsidered + "," + finder.mapSize() + "," + path.length() + "," + end.cost + "," + hash;
    }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Baritone.settings().chatDebug.value = false;
        Baritone.settings().considerPotionEffects.value = false;
        Baritone.settings().allowPlace.value = false;
        Baritone.settings().slowPath.value = false;
        configureSize(Integer.parseInt(args[3]));
        int warmup = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
        for (String kind : args[0].split(";")) measure(kind, warmup, samples);
    }
    static void configureSize(int size) {
        try {
            Object setting = Baritone.settings().getClass().getField("pathingCacheSize").get(Baritone.settings());
            setting.getClass().getField("value").set(setting, size);
        } catch (NoSuchFieldException absentOnBaseline) {
            if (size != 65536) throw new IllegalArgumentException("baseline only supports 65536", absentOnBaseline);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
    static void measure(String kind, int warmup, int samples) {
        int batch = kind.equals("short") ? 100 : 1;
        String expected = run(kind);
        for (int i = 0; i < warmup; i++) for (int j = 0; j < batch; j++) if (!expected.equals(run(kind))) throw new AssertionError("unstable result");
        for (int i = 0; i < samples; i++) {
            long bytes = ALLOC.getCurrentThreadAllocatedBytes(), start = System.nanoTime();
            for (int j = 0; j < batch; j++) if (!expected.equals(run(kind))) throw new AssertionError("unstable result");
            long elapsed = System.nanoTime() - start, allocated = ALLOC.getCurrentThreadAllocatedBytes() - bytes;
            System.out.printf("RESULT,walking,%s,%d,%.6f,%d,%s,%d%n", kind, i, elapsed / 1e6 / batch, allocated / batch, expected, lastMisses);
        }
    }
}
