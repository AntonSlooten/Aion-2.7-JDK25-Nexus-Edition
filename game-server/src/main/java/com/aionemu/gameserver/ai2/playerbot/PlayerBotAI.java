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
package com.aionemu.gameserver.ai2.playerbot;

import java.util.List;

import com.aionemu.commons.network.util.ThreadPoolManager;
import com.aionemu.gameserver.ai2.AIName;
import com.aionemu.gameserver.ai2.AIState;
import com.aionemu.gameserver.ai2.AITemplate;
import com.aionemu.gameserver.ai2.poll.AIAnswer;
import com.aionemu.gameserver.ai2.poll.AIAnswers;
import com.aionemu.gameserver.ai2.poll.AIQuestion;
import com.aionemu.gameserver.configs.main.CompanionConfig;
import com.aionemu.gameserver.controllers.movement.BotMoveController;
import com.aionemu.gameserver.model.EmotionType;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.BotPlayer;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.network.aion.serverpackets.SM_EMOTION;
import com.aionemu.gameserver.services.player.PlayerReviveService;
import com.aionemu.gameserver.utils.MathUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI driving a {@link BotPlayer} companion: follows its host with {@link BotMoveController} and, once
 * in range, picks skills via {@link PlayerBotSkillSelector}. Deliberately does NOT extend NpcAI2 - that
 * class and the ai2.manager.* attack/follow managers are hard-typed to Npc and unusable for a
 * Player-owned AI. Ticked periodically by {@link PlayerBotAITaskManager} rather than the reactive
 * Npc-only event chain that normally drives think().
 */
@AIName("playerbot")
public class PlayerBotAI extends AITemplate {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotAI.class);

	private BotPlayer owner() {
		return (BotPlayer) getOwner();
	}

	private Player resolveHost() {
		return World.getInstance().findPlayer(owner().getHostObjectId());
	}

	@Override
	protected AIAnswer pollInstance(AIQuestion question) {
		// Must short-circuit here: the inherited isDestinationReached() casts owner to Npc/NpcAI2
		// in its FIGHT/RETURNING/WALKING branches and would ClassCastException on a bot Player.
		//
		// Delegates to BotMoveController.isAtDestination() rather than recomputing a threshold here -
		// the move controller's own follow distance is now dynamic (host-following vs. closing on a
		// combat target use different stop distances), and MoveTaskManager deregisters the bot from
		// active movement the instant this returns positive, so whatever threshold decides "arrived"
		// MUST be the exact same one moveToDestination() uses, or `started` can get wedged true forever
		// (confirmed via live tracing - this exact class of bug already happened once).
		if (question == AIQuestion.DESTINATION_REACHED) {
			BotMoveController moveController = (BotMoveController) owner().getMoveController();
			boolean arrived = moveController.isAtDestination();
			if (arrived)
				moveController.markArrived();
			return arrived ? AIAnswers.POSITIVE : AIAnswers.NEGATIVE;
		}
		return null;
	}

	@Override
	protected void handleDied() {
		setStateIfNot(AIState.DIED);
		final BotPlayer bot = owner();
		ThreadPoolManager.getInstance().schedule(new Runnable() {
			@Override
			public void run() {
				if (bot.getLifeStats().isAlreadyDead()) {
					PlayerReviveService.revive(bot, 100, 100, false);
				}
				setStateIfNot(AIState.IDLE);
			}
		}, CompanionConfig.BOT_REVIVE_DELAY * 1000L);
	}

	@Override
	protected void handleAttack(Creature creature) {
		setStateIfNot(AIState.FIGHT);
	}

	@Override
	protected void handleCreatureAggro(Creature creature) {
		setStateIfNot(AIState.FIGHT);
	}

	@Override
	protected void handleTargetGiveup() {
		setStateIfNot(AIState.IDLE);
	}

	/**
	 * Keeps the bot's flying state matched to its host's. A bot has no client to send CM_EMOTION(FLY)/
	 * (LAND) itself, so this drives the same FlyController entry points those packets normally trigger.
	 * Cheap to call every tick - startFly()/endFly() only act on the actual state transition edge.
	 * BotPlayer is exempt from flight-energy drain (see BotPlayer's constructor), so there's no need to
	 * keep topping up FP here.
	 */
	private void syncFlight(BotPlayer bot, Player host) {
		// isFlying() (Creature's - Player does NOT override it; the only "flying"-named member on Player
		// itself is the unrelated, dead isFlying field behind setFlyingMode()/isInFlyingMode(), which
		// nothing in the engine ever calls) covers BOTH full hover-flight (CreatureState.FLYING) and
		// gliding (CreatureState.GLIDING - jump + wings, the way a player normally gets airborne before
		// optionally pressing the separate hover toggle). isInFlyingState() alone only covers the former,
		// which is why a host who was gliding rather than hovering was never detected as airborne at all
		// - confirmed live via diagnostic logging: host FLYING=false read continuously while genuinely
		// airborne.
		boolean hostAirborne = host.isFlying();
		// Host FP is confirmed draining live while flying, which only happens via FlyController.
		// startFly()'s triggerFpReduce() call - i.e. CreatureState.FLYING almost certainly WAS set on
		// SOME Player object. Logging identity + currentFp alongside the state read to check whether
		// resolveHost() (World.findPlayer(hostObjectId)) is even returning that same object.
		if (CompanionConfig.DEBUG_LOGGING)
			log.info("[bot {}] syncFlight check: host={}@{} FLYING={} GLIDING={} isFlying={} currentFp={} | bot isInFlyingState={}",
				bot.getObjectId(), host.getName(), System.identityHashCode(host), host.isInState(CreatureState.FLYING),
				host.isInState(CreatureState.GLIDING), hostAirborne, host.getLifeStats().getCurrentFp(), bot.isInFlyingState());
		if (hostAirborne && !bot.isInFlyingState()) {
			bot.getFlyController().startFly();
		}
		else if (!hostAirborne && bot.isInFlyingState()) {
			bot.getFlyController().endFly();
		}
	}

	/**
	 * Formation spread radius (units) around the host that idle-following bots fan out into, instead of
	 * all converging on the host's exact point. Requested live: "cause the Companions to spread out once
	 * they reach their end destination. It is a real eyesore to have them stand right ontop of each
	 * other and me."
	 */
	private static final float FORMATION_RADIUS = 1.3f;

	/**
	 * A stable angular slot around the host, evenly spaced by this bot's index within host.getBots() (an
	 * insertion-ordered list, so a given bot keeps the same slot for the life of the session rather than
	 * jittering between ticks). Not geo-aware - same straight-line limitation the rest of this move
	 * controller already has - but good enough to stop bots stacking on top of each other and the host.
	 */
	private float[] formationOffset(BotPlayer bot, Player host) {
		List<Player> bots = host.getBots();
		int index = Math.max(bots.indexOf(bot), 0);
		int total = Math.max(bots.size(), 1);
		double angle = 2 * Math.PI * index / total;
		return new float[] { (float) (Math.cos(angle) * FORMATION_RADIUS), (float) (Math.sin(angle) * FORMATION_RADIUS) };
	}

	/**
	 * Drawing a weapon (CreatureState.WEAPON_EQUIPPED + attack-mode) is normally 100% client-driven:
	 * a real client sends CM_EMOTION(ATTACKMODE) the instant the player presses attack
	 * (network/aion/clientpackets/CM_EMOTION.java). A bot has no client to ever send that, so without
	 * this it stays in its neutral stance forever regardless of how correct the actual damage/SM_ATTACK
	 * path is underneath - confirmed live: bots were dealing/receiving combat fine but never visually
	 * looked armed. NPCs hit the identical problem and have their own equivalent
	 * (ai2/manager/EmoteManager.emoteStartAttacking/emoteStopAttacking, Npc-typed so not reusable here).
	 */
	private void syncCombatStance(BotPlayer bot, Creature engageTarget) {
		if (engageTarget != null) {
			if (!bot.isInState(CreatureState.WEAPON_EQUIPPED)) {
				bot.setAttackMode(true);
				bot.setState(CreatureState.WEAPON_EQUIPPED);
				PacketSendUtility.broadcastPacket(bot, new SM_EMOTION(bot, EmotionType.ATTACKMODE, 0, engageTarget.getObjectId()));
			}
		}
		else if (bot.isInState(CreatureState.WEAPON_EQUIPPED)) {
			bot.setAttackMode(false);
			bot.unsetState(CreatureState.WEAPON_EQUIPPED);
			PacketSendUtility.broadcastPacket(bot, new SM_EMOTION(bot, EmotionType.NEUTRALMODE, 0, 0));
		}
	}

	@Override
	public void think() {
		if (!tryLockThink()) {
			log.warn("[bot {}] think() skipped: lock already held (state={})", getObjectId(), getState());
			return;
		}
		try {
			if (isInState(AIState.DIED))
				return;

			BotPlayer bot = owner();
			if (bot.getLifeStats().isAlreadyDead())
				return;

			// A real client naturally does nothing else while a cast animation is playing - it can't
			// queue a second action mid-cast. A bot has no such pause: think() re-runs the whole decision
			// cascade every ~750ms regardless of whether a previous multi-second cast is still in
			// progress, and Skill.canUseSkill() has no "already casting something" guard at all - it
			// unconditionally calls effector.setCasting(this) the instant ANY new cast is attempted,
			// silently stomping whatever was already casting. For a mixed melee/ranged kit (e.g. a
			// Chanter's ranged "Smite"), the very next tick could pick a different action (often the
			// basic-attack fallback once the in-progress skill itself looks unavailable mid-cast) and
			// cancel a cast that was already most of the way through, wasting the time already spent.
			// Confirmed live: "mid way through casting the casting stops and then begins a melee attack,
			// wasting the casting time already spent." Skipping the whole tick while casting - not just
			// the skill-selection part - also avoids movement (PlayerMovedCondition-style skills already
			// cancel on move for the same real-client-parity reason) triggering the same kind of
			// self-interruption.
			if (bot.isCasting())
				return;

			Player host = resolveHost();
			if (host == null) {
				log.warn("[bot {}] think(): host {} not resolvable via World.findPlayer", getObjectId(), bot.getHostObjectId());
				return;
			}

			// Host is on a scripted flight transport (the automated flying shuttle between zones) -
			// the bot can't meaningfully "follow" a path it doesn't know and isn't actually moving along
			// under the host's own control, so trying to chase just looked like frantic running-in-place
			// until CompanionService.relocateBots() (PlayerController.onFlyTeleportEnd()) teleports the
			// bot to the destination the instant the host lands. Confirmed live: "they still run and
			// follow, but are summoned when you land" - stand still and wait for that instead.
			if (host.isInState(CreatureState.FLIGHT_TELEPORT))
				return;

			syncFlight(bot, host);

			BotMoveController moveController = (BotMoveController) bot.getMoveController();
			Creature engageTarget = PlayerBotSkillSelector.resolveEngageTarget(bot, host);
			syncCombatStance(bot, engageTarget);

			// No-ops instantly for any non-Spirit-Master bot (no owned SkillSubType.SUMMON skill matching
			// a pact-spirit stack marker) - see PlayerBotSummonSelector's own javadoc for why this is kept
			// separate from the class-agnostic chooseAndCastSkill() cascade below.
			PlayerBotSummonSelector.manageSummon(bot, host, engageTarget);

			if (engageTarget != null) {
				// Close to the bot's own effective engage range - short for melee-only kits (so they
				// actually get in swinging distance), the range of its best ranged skill for casters/
				// healers instead of the raw (melee-short) weapon range every weapon type in this engine
				// otherwise reports, so they don't get walked into melee range they never needed.
				// Requested live: "if the host is a melee character, the mages will stay close and in
				// turn get close to the enemy."
				float attackRange = PlayerBotSkillSelector.effectiveEngageRange(bot);
				boolean inRange = MathUtil.isIn3dRange(bot, engageTarget, attackRange);
				if (CompanionConfig.DEBUG_LOGGING)
					log.info("[bot {}] engage check: mainHandWeapon={}, attackRange={}, dist={}, inRange={}",
						getObjectId(), bot.getEquipment().getMainHandWeapon(), attackRange,
						MathUtil.getDistance(bot, engageTarget), inRange);
				if (!inRange) {
					if (setStateIfNot(AIState.FOLLOWING) && CompanionConfig.DEBUG_LOGGING)
						log.info("[bot {}] think(): closing on combat target, state->FOLLOWING", getObjectId());
					moveController.moveToTargetObject(engageTarget, attackRange);
					return;
				}
			}
			else {
				// Outside combat, a hurt/afflicted group member who's simply wandered off (exploring,
				// gathering, etc.) used to never get reached: nothing but engageTarget/host was ever a
				// movement target, so a heal/cleanse cast was attempted every tick and silently failed on
				// range forever. Confirmed live: "she doesn't heal anyone outside of battle".
				Player supportTarget = PlayerBotSkillSelector.findSupportApproachTarget(bot, host);
				if (supportTarget != null) {
					if (setStateIfNot(AIState.FOLLOWING) && CompanionConfig.DEBUG_LOGGING)
						log.info("[bot {}] think(): approaching {} for heal/cleanse, state->FOLLOWING",
							getObjectId(), supportTarget.getName());
					moveController.moveToTargetObject(supportTarget);
					return;
				}
			}

			boolean acted = PlayerBotSkillSelector.chooseAndCastSkill(bot, host);

			// Deferred until after the cast attempt above (rather than an upfront "not in range, go to
			// host" check) so that reaching a support-approach target long enough to actually heal/cleanse
			// them takes priority over immediately walking back to the host - otherwise a host who'd
			// stayed behind while the bot closed on a wandered-off ally could yank the bot away again on
			// the very tick it finally arrived, before it ever got to cast.
			//
			// Uses this bot's own formation slot rather than FollowEventHandler.isInRange(this, host)
			// (raw distance-to-host, no offset) - that generic NPC-follow helper's fixed range wouldn't
			// match a formation point sitting FORMATION_RADIUS away from the host, which would leave the
			// bot re-registering with MoveTaskManager and never settling into FOLLOWING->IDLE every tick.
			// Reuses BotMoveController.FOLLOW_OFFSET as the "close enough" threshold so this stays in sync
			// with the move controller's own arrival check (see that class's note on why the two must
			// match).
			if (engageTarget == null && !acted) {
				float[] offset = formationOffset(bot, host);
				float destX = host.getX() + offset[0];
				float destY = host.getY() + offset[1];
				boolean atFormationSpot = MathUtil.getDistance(bot.getX(), bot.getY(), bot.getZ(), destX, destY, host.getZ()) <= BotMoveController.FOLLOW_OFFSET;
				if (!atFormationSpot) {
					if (setStateIfNot(AIState.FOLLOWING) && CompanionConfig.DEBUG_LOGGING)
						log.info("[bot {}] think(): out of range of host, state->FOLLOWING", getObjectId());
					moveController.moveToTargetObject(host, BotMoveController.FOLLOW_OFFSET, offset[0], offset[1]);
					return;
				}
			}

			if (setStateIfNot(acted ? AIState.FIGHT : AIState.IDLE) && CompanionConfig.DEBUG_LOGGING)
				log.info("[bot {}] think(): in range, acted={}, state->{}", getObjectId(), acted, getState());
		}
		finally {
			unlockThink();
		}
	}
}
