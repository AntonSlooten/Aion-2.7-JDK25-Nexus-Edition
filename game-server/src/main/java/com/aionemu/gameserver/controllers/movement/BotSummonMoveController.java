/*
 * This file is part of aion-lightning <aion-lightning.com>.
 *
 *  aion-lightning is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  aion-lightning is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with aion-lightning.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.controllers.movement;

import com.aionemu.gameserver.model.gameobjects.Summon;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MOVE;
import com.aionemu.gameserver.taskmanager.tasks.MoveTaskManager;
import com.aionemu.gameserver.utils.MathUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.stats.StatFunctions;
import com.aionemu.gameserver.world.World;

/**
 * Move controller for a bot-owned {@link Summon}: a real summon only ever moves in response to
 * {@link com.aionemu.gameserver.network.aion.clientpackets.CM_SUMMON_MOVE} - the client-side game
 * computes where the pet should walk to stay near its master and sends the server raw coordinates. A
 * bot has no client to ever send that (the exact same problem {@link BotMoveController} solves for the
 * bot itself), so a bot-owned pet spawned with the default {@link SummonMoveController} just sits at its
 * spawn point forever. Confirmed live: "the summon also does not follow anyone".
 *
 * Mirrors BotMoveController's own shape exactly (continuously re-reads a live follow target's position,
 * driven by {@link MoveTaskManager} at its normal 100ms tick) rather than being polled ad hoc from
 * PlayerBotSummonSelector's own ~750ms think() cadence, which was tried first and confirmed live to look
 * janky ("it just teleports every 10 meters or so"): a 750ms-interval position snap with no interpolation
 * in between is visually a series of jumps, not smooth movement, and a fast pet (run_speed 11 for Wind)
 * covers a real, jarring distance in that gap. This requires the Summon to have a working AI2 - see
 * {@link com.aionemu.gameserver.ai2.playerbot.PlayerBotSummonAI}'s own javadoc for why one wasn't already
 * assigned to any Summon anywhere in this codebase.
 */
public class BotSummonMoveController extends SummonMoveController {

	/** How close is "caught up" when just following its master out of combat - wider than a
	 * player-following bot's own FOLLOW_OFFSET since a pet trailing behind doesn't need to be as tight. */
	public static final float FOLLOW_OFFSET = 3f;

	private volatile VisibleObject followTarget;
	private volatile float followStopDistance = FOLLOW_OFFSET;

	public BotSummonMoveController(Summon owner) {
		super(owner);
	}

	/**
	 * Follow/approach a target, stopping once within stopDistance - the bot's own position out of combat,
	 * or its current engage target (at the pet's own best attack range) once there's a fight. Safe to call
	 * every think() tick even while already registered: only started's false->true edge triggers
	 * (re-)registration, but followTarget/followStopDistance update every call so an active chase always
	 * tracks the latest target and distance - identical contract to BotMoveController.moveToTargetObject().
	 */
	public void moveToTargetObject(VisibleObject target, float stopDistance) {
		this.followTarget = target;
		this.followStopDistance = stopDistance;
		if (started.compareAndSet(false, true)) {
			updateLastMove();
			MoveTaskManager.getInstance().addCreature(owner);
		}
	}

	/** Single source of truth for "have I arrived" - see BotMoveController.isAtDestination()'s identical
	 * note on why pollInstance()/moveToDestination() must never diverge on this. */
	public boolean isAtDestination() {
		VisibleObject target = followTarget;
		return target == null || MathUtil.getDistance(owner, target) <= followStopDistance;
	}

	/** Called by PlayerBotSummonAI.pollInstance() exactly when it answers DESTINATION_REACHED positively -
	 * see BotMoveController.markArrived()'s identical note on the one-tick race this closes. */
	public void markArrived() {
		started.set(false);
	}

	@Override
	public void moveToDestination() {
		VisibleObject target = followTarget;
		if (target == null || !owner.canPerformMove()) {
			if (started.compareAndSet(true, false)) {
				setAndSendStopMove(owner);
			}
			updateLastMove();
			return;
		}

		if (MathUtil.getDistance(owner, target) <= followStopDistance) {
			started.set(false);
			updateLastMove();
			return;
		}

		boolean directionChanged = target.getX() != targetDestX || target.getY() != targetDestY || target.getZ() != targetDestZ;
		targetDestX = target.getX();
		targetDestY = target.getY();
		targetDestZ = target.getZ();

		float x = owner.getX();
		float y = owner.getY();
		float z = owner.getZ();

		float currentSpeed = StatFunctions.getMovementModifier(owner, StatEnum.SPEED, owner.getGameStats().getMovementSpeedFloat());
		float futureDistPassed = currentSpeed * (System.currentTimeMillis() - lastMoveUpdate) / 1000f;
		float dist = (float) MathUtil.getDistance(x, y, z, targetDestX, targetDestY, targetDestZ);

		if (dist == 0) {
			updateLastMove();
			return;
		}
		if (futureDistPassed > dist) {
			futureDistPassed = dist;
		}

		float distFraction = futureDistPassed / dist;
		float newX = (targetDestX - x) * distFraction + x;
		float newY = (targetDestY - y) * distFraction + y;
		float newZ = (targetDestZ - z) * distFraction + z;
		heading = (byte) (Math.toDegrees(Math.atan2(newY - y, newX - x)) / 3);

		// updateKnownList=true for the same reason BotMoveController uses it - see that class's note.
		World.getInstance().updatePosition(owner, newX, newY, newZ, heading, true);

		if (directionChanged) {
			movementMask = -32;
			PacketSendUtility.broadcastPacket(owner, new SM_MOVE(owner));
		}

		updateLastMove();
	}

	@Override
	public void abortMove() {
		started.set(false);
		followTarget = null;
		followStopDistance = FOLLOW_OFFSET;
		MoveTaskManager.getInstance().removeCreature(owner);
		targetDestX = 0;
		targetDestY = 0;
		targetDestZ = 0;
		setAndSendStopMove(owner);
	}
}
