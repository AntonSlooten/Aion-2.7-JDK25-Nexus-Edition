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

import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.BotPlayer;
import com.aionemu.gameserver.model.stats.container.StatEnum;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MOVE;
import com.aionemu.gameserver.taskmanager.tasks.MoveTaskManager;
import com.aionemu.gameserver.utils.MathUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.stats.StatFunctions;
import com.aionemu.gameserver.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Move controller for {@link BotPlayer}s: unlike {@link PlayableMoveController}'s static
 * setNewDirection destination, this continuously re-reads a live follow target's position every
 * tick (mirroring {@link NpcMoveController}'s TARGET_OBJECT tracking) and is driven by
 * {@link MoveTaskManager} instead of PlayerMoveTaskManager, since the latter only supports
 * snapping to a fixed point.
 */
public class BotMoveController extends PlayerMoveController {

	private static final Logger log = LoggerFactory.getLogger(BotMoveController.class);

	/**
	 * Distance at which the bot is considered "caught up" and stops moving. {@link PlayerBotAI}'s
	 * pollInstance(DESTINATION_REACHED) MUST use this exact same value - MoveTaskManager deregisters
	 * the bot from active movement based on that poll answer, so if the two thresholds ever diverge,
	 * MoveTaskManager can remove the bot (clearing its own bookkeeping) before this controller's own
	 * arrival branch - the one that resets `started` - ever runs, permanently wedging `started` at
	 * true and silently no-opping every future moveToTargetObject() call. (This happened: range=2 vs
	 * FOLLOW_OFFSET=1.5 meant bots would follow exactly once, then never move again.)
	 */
	public static final float FOLLOW_OFFSET = 1.5f;

	private volatile VisibleObject followTarget;
	private volatile float followStopDistance = FOLLOW_OFFSET;

	public BotMoveController(BotPlayer owner) {
		super(owner);
	}

	/**
	 * Follow at the default host-following distance ({@link #FOLLOW_OFFSET}).
	 */
	public void moveToTargetObject(VisibleObject target) {
		moveToTargetObject(target, FOLLOW_OFFSET);
	}

	/**
	 * Follow/approach a target, stopping once within stopDistance - e.g. a melee bot closing in on its
	 * combat target needs a much shorter stop distance than the default host-following one, while a
	 * ranged/caster bot already close enough via host-following shouldn't be walked into melee range
	 * unnecessarily. Safe to call every think() tick even while already registered: only started's
	 * false->true edge triggers (re-)registration, but followTarget/followStopDistance update every
	 * call so an active chase always tracks the latest target and distance.
	 */
	public void moveToTargetObject(VisibleObject target, float stopDistance) {
		this.followTarget = target;
		this.followStopDistance = stopDistance;
		if (started.compareAndSet(false, true)) {
			log.info("[bot {}] REGISTERED with MoveTaskManager", owner.getObjectId());
			updateLastMove();
			MoveTaskManager.getInstance().addCreature(owner);
		}
	}

	/**
	 * Single source of truth for "have I arrived", used by both moveToDestination()'s own early-return
	 * and PlayerBotAI.pollInstance()'s DESTINATION_REACHED answer. These two must never diverge - see
	 * markArrived()'s note - so pollInstance() delegates here instead of recomputing its own threshold.
	 */
	public boolean isAtDestination() {
		VisibleObject target = followTarget;
		return target == null || MathUtil.getDistance(owner, target) <= followStopDistance;
	}

	/**
	 * Called by {@link com.aionemu.gameserver.ai2.playerbot.PlayerBotAI#pollInstance} exactly when it
	 * answers DESTINATION_REACHED positively - i.e. exactly when MoveTaskManager is about to deregister
	 * this controller from active movement. Resetting `started` here (rather than only inside
	 * moveToDestination()'s own pre-move arrival check) closes a one-tick race: a single movement step
	 * can carry the bot from outside FOLLOW_OFFSET to inside it, so the *next* poll (using the
	 * already-moved position) reports arrival before moveToDestination() ever re-checks and resets the
	 * flag itself. Without this, MoveTaskManager could deregister the bot while `started` was still
	 * true, permanently wedging future moveToTargetObject() calls into a silent no-op.
	 */
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
			// Caught up to the target: clear started so the next moveToTargetObject() call (once the
			// host wanders off again) re-registers with MoveTaskManager instead of silently no-opping.
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

		// updateKnownList=true: a real player's own movement (via CM_MOVE) keeps refreshing and
		// reciprocally registering itself into nearby NPCs' known-lists (KnownList.add() is mutual - the
		// side that runs the scan adds the OTHER side to both). A bot's movement never did that here,
		// which was harmless for anything already known by the TIME it arrived, but for a stationary
		// "general"-AI mob whose own known-list only ever refreshes on spawn/region-activation or when
		// IT walks, a bot that showed up afterward was NEVER added at all - AggroList.getMostHated()
		// zeroes out any attacker's hate the instant it isn't "known" (getKnownList().knowns()), so the
		// mob's own chooseAttackIntention() saw no valid target and gave up instantly, every single hit,
		// permanently. KnownList.doUpdate() has its own 1s internal throttle regardless of call
		// frequency, so flipping this to true is safe to call on every movement tick. Confirmed live,
		// traced end to end: "the currently targeted mob is suffering from the issue. Just will not
		// target me... it never turns to attack any of my bots."
		World.getInstance().updatePosition(owner, newX, newY, newZ, heading, true);

		// Unlike a real player's client (which drives its own visible movement) or an NPC (whose
		// NpcMoveController does this same broadcast), a bot has no client - without this, other
		// players' clients never learn the bot moved at all, no matter how correct the server-side
		// position math is.
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
