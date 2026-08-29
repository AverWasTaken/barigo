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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.utils.BlockStateInterface;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;

public abstract class Movement implements IMovement, MovementHelper {

    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    protected final IBaritone baritone;
    protected final IPlayerContext ctx;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    protected final BetterBlockPos src;

    protected final BetterBlockPos dest;

    /**
     * The positions that need to be broken before this movement can ensue
     */
    protected final BetterBlockPos[] positionsToBreak;

    /**
     * The position where we need to place a block before this movement can ensue
     */
    protected final BetterBlockPos positionToPlace;

    private Double cost;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    private Set<BetterBlockPos> validPositionsCached = null;

    private Boolean calculatedWhileLoaded;

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak, BetterBlockPos toPlace) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(baritone, src, dest, toBreak, null);
    }

    public double getCost() throws NullPointerException {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    public abstract double calculateCost(CalculationContext context);

    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    public void override(double cost) {
        this.cost = cost;
    }

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    protected boolean playerInValidPosition() {
        return getValidPositions().contains(ctx.playerFeet()) || getValidPositions().contains(((PathingBehavior) baritone.getPathingBehavior()).pathStart());
    }

    /**
     * Handles the execution of the latest Movement
     * State, and offers a Status to the calling class.
     *
     * @return Status
     */
    @Override
    public MovementStatus update() {
        ctx.player().getAbilities().flying = false;
        currentState = updateState(currentState);
        if (MovementHelper.isLiquid(ctx, ctx.playerFeet())) {
            if (shouldSwim()) {
                // sprint is what enters and keeps the swim state. vanilla only STARTS sprinting from
                // the key while on the ground or eye-underwater, and actively cancels it at the water
                // surface (which is exactly where we used to bob), so arm the flag ourselves.
                // updateSwimming is hooked so the swim state latches the moment we want to dive,
                // instead of bobbing around waiting for gravity to pull the eye under
                currentState.setInput(Input.SPRINT, true);
                ctx.player().setSprinting(true);
                if (currentState.getStatus() == MovementStatus.RUNNING) {
                    // pitch is the up/down control while swimming — aim straight at the dest, or
                    // straight up when the air clock says it's time to breathe (moveRelative is
                    // yaw-only, so surfacing still makes forward progress)
                    float aimPitch = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()).getPitch();
                    final float pitch = shouldSurface() ? -90 : aimPitch;
                    currentState.getTarget().getRotation().ifPresent(rotation ->
                            currentState.setTarget(new MovementState.MovementTarget(
                                    rotation.withPitch(pitch),
                                    false
                            ))
                    );
                    if (pitch < 0) {
                        // swimming up: vanilla skips the swim-up pull at the surface unless jumping
                        currentState.setInput(Input.JUMP, true);
                    }
                }
                if (!ctx.player().isSwimming() && ctx.player().isOnGround() && ctx.player().position().y < dest.y + 0.6) {
                    // fallback: swim state never latched and we're on the bottom — the old bob
                    currentState.setInput(Input.JUMP, true);
                }
            } else if (ctx.player().position().y < dest.y + 0.6) {
                currentState.setInput(Input.JUMP, true);
            }
        }
        if (ctx.player().isInWall()) {
            ctx.getSelectedBlock().ifPresent(pos -> MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos)));
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        // If the movement target has to force the new rotations, or we aren't using silent move, then force the rotations
        currentState.getTarget().getRotation().ifPresent(rotation ->
                baritone.getLookBehavior().updateTarget(
                        rotation,
                        currentState.getTarget().hasToForceRotations()));
        baritone.getInputOverrideHandler().clearAllKeys();
        currentState.getInputStates().forEach((input, forced) -> {
            baritone.getInputOverrideHandler().setInputForceState(input, forced);
        });
        currentState.getInputStates().clear();

        // If the current status indicates a completed movement
        if (currentState.getStatus().isComplete()) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        return currentState.getStatus();
    }

    /**
     * Air ticks (of the 300 max) at which we head for the surface mid-swim — 100 leaves 5 seconds of
     * margin before drowning damage starts, and surfacing from a reasonable depth takes ~1 second
     */
    private static final int SURFACE_AT_AIR = 100;

    /**
     * Holding sprint is what keeps the vanilla swim state alive, so swimming is only on the table when
     * we're actually in water, sprinting is allowed, and we have the hunger to sprint
     */
    private boolean shouldSwim() {
        return Baritone.settings().allowSwimming.value
                && ctx.player().isInWater()
                && Baritone.settings().allowSprint.value
                && ctx.player().getFoodData().getFoodLevel() > 6;
    }

    /**
     * Air is a clock while the eye is underwater: head for the surface once it's running low. And once
     * we're up, stay up until it's actually refilled — otherwise we'd instantly dive again and just
     * porpoise on the spot instead of getting a real breath
     */
    private boolean shouldSurface() {
        if (ctx.player().isUnderWater()) {
            return ctx.player().getAirSupply() < SURFACE_AT_AIR;
        }
        return ctx.player().isSwimming() && ctx.player().getAirSupply() < ctx.player().getMaxAirSupply();
    }

    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        boolean somethingInTheWay = false;
        for (BetterBlockPos blockPos : positionsToBreak) {
            if (!ctx.world().getEntitiesOfClass(FallingBlockEntity.class, new AABB(0, 0, 0, 1, 1.1, 1).move(blockPos)).isEmpty() && Baritone.settings().pauseMiningForFallingBlocks.value) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(ctx, blockPos)) { // can't break air, so don't try
                somethingInTheWay = true;
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, blockPos));
                Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos, ctx.playerController().getBlockReachDistance());
                if (reachable.isPresent()) {
                    Rotation rotTowardsBlock = reachable.get();
                    state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));
                    if (ctx.isLookingAt(blockPos) || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)) {
                        state.setInput(Input.CLICK_LEFT, true);
                    }
                    return false;
                }
                //get rekt minecraft
                //i'm doing it anyway
                //i dont care if theres snow in the way!!!!!!!
                //you dont own me!!!!
                state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        VecUtils.getBlockPosCenter(blockPos), ctx.playerRotations()), true)
                );
                // don't check selectedblock on this one, this is a fallback when we can't see any face directly, it's intended to be breaking the "incorrect" block
                state.setInput(Input.CLICK_LEFT, true);
                return false;
            }
        }
        if (somethingInTheWay) {
            // There's a block or blocks that we can't walk through, but we have no target rotation to reach any
            // So don't return true, actually set state to unreachable
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        return true;
    }

    @Override
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    @Override
    public BetterBlockPos getSrc() {
        return src;
    }

    @Override
    public BetterBlockPos getDest() {
        return dest;
    }

    @Override
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
    }

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    @Override
    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.bsi.worldContainsLoadedChunk(dest.x, dest.z);
    }

    @Override
    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    @Override
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(BlockStateInterface bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(bsi, positionToPlace.x, positionToPlace.y, positionToPlace.z)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(BlockStateInterface bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }
}
