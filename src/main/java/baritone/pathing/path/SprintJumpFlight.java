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

import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class SprintJumpFlight {

    private final BiConsumer<Input, Boolean> input;
    private final Consumer<Rotation> look;
    private final Consumer<BlockPos> armJump;
    private BlockPos direction;

    SprintJumpFlight(BiConsumer<Input, Boolean> input, Consumer<Rotation> look, Consumer<BlockPos> armJump) {
        this.input = input;
        this.look = look;
        this.armJump = armJump;
    }

    void start(BlockPos direction, float pitch) {
        this.direction = new BlockPos(direction.getX(), 0, direction.getZ());
        apply(pitch);
    }

    boolean continueFlight(boolean airborne, boolean allowed, float pitch) {
        if (!airborne || !allowed) {
            clear(); // a landing never authorizes another jump; the runway must be checked again
        }
        if (!active()) {
            return false;
        }
        apply(pitch);
        return true;
    }

    private void apply(float pitch) {
        // a new movement can clear W/space or aim backwards at a waypoint we've already crossed.
        input.accept(Input.MOVE_BACK, false);
        input.accept(Input.MOVE_LEFT, false);
        input.accept(Input.MOVE_RIGHT, false);
        input.accept(Input.MOVE_FORWARD, true);
        input.accept(Input.JUMP, true);
        look.accept(rotation(direction, pitch));
        armJump.accept(direction);
    }

    static Rotation rotation(BlockPos direction, float pitch) {
        return new Rotation((float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ())), pitch);
    }

    boolean active() {
        return direction != null;
    }

    void clear() {
        direction = null;
    }

    void copyFrom(SprintJumpFlight previous) {
        direction = previous.direction;
    }
}
