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
package com.aionemu.gameserver.ai2.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai2.AI2Logger;
import com.aionemu.gameserver.ai2.AIState;
import com.aionemu.gameserver.ai2.AISubState;
import com.aionemu.gameserver.ai2.NpcAI2;
import com.aionemu.gameserver.ai2.manager.AttackManager;
import com.aionemu.gameserver.ai2.manager.EmoteManager;
import com.aionemu.gameserver.ai2.manager.WalkManager;
import com.aionemu.gameserver.ai2.poll.AIQuestion;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;

/**
 * @author ATracer
 */
public class AttackEventHandler {

	private static final Logger log = LoggerFactory.getLogger(AttackEventHandler.class);

	/**
	 * @param npcAI
	 * @param creature
	 */
	public static void onAttack(NpcAI2 npcAI, Creature creature) {
		if (npcAI.isLogging()) {
			AI2Logger.info(npcAI, "onAttack");
		}
		if (creature == null || creature.getLifeStats().isAlreadyDead()) {
			return;
		}
		// A companion bot fighting alongside its host means several attackers can land hits on the SAME
		// npc at essentially the same instant, each dispatched on its own worker thread - something a
		// single real client, limited to one packet at a time, could never produce. setStateIfNot() below
		// is internally synchronized, but everything AROUND it (the isInState()/canThink() checks,
		// setTarget(), the aggro-based retarget decision, and the log line reading the state back
		// afterward) was not, so two concurrent calls could interleave their reads and writes: confirmed
		// live via this class's own diagnostic logging - one thread read "state=FIGHT, targeting Summon"
		// then, a fraction of a millisecond later on a DIFFERENT thread's call, the SAME npc read back as
		// "state=IDLE, targeting null" mid-decision, meaning something reset it between two concurrent
		// hits landing. Synchronizing the whole decide-then-act sequence on the npcAI instance makes each
		// npc's target/state decision atomic per hit, however many attackers land on it simultaneously.
		// Requested live, following a report of a monster that would take damage from bots indefinitely
		// but never visibly turn to fight back: "there we go" (repro caught with this diagnostic).
		synchronized (npcAI) {
			// TODO lock or better switch
			if (npcAI.isInState(AIState.RETURNING)) {
				// TODO add to aggrolist?
				log.info("[aggro] npc={} objId={} onAttack() from {} bailed: AI state=RETURNING",
					npcAI.getOwner().getName(), npcAI.getOwner().getObjectId(), creature.getName());
				return;
			}
			if (!npcAI.canThink()) {
				log.info("[aggro] npc={} objId={} onAttack() from {} bailed: canThink()=false (state={})",
					npcAI.getOwner().getName(), npcAI.getOwner().getObjectId(), creature.getName(), npcAI.getState());
				return;
			}
			if (npcAI.isInState(AIState.WALKING)) {
				WalkManager.stopWalking(npcAI);
			}
			npcAI.getOwner().getGameStats().renewLastAttackedTime();
			if (npcAI.setStateIfNot(AIState.FIGHT)) {
				if (npcAI.isLogging()) {
					AI2Logger.info(npcAI, "onAttack() -> startAttacking");
				}
				npcAI.setSubStateIfNot(AISubState.NONE);
				npcAI.getOwner().setTarget(creature);
				log.info("[aggro] npc={} objId={} onAttack() from {} -> FIGHT, target set, startAttacking()",
					npcAI.getOwner().getName(), npcAI.getOwner().getObjectId(), creature.getName());
				AttackManager.startAttacking(npcAI);
				if (npcAI.poll(AIQuestion.CAN_SHOUT))
					ShoutEventHandler.onAttackBegin(npcAI, (Creature) npcAI.getOwner().getTarget());
			}
			// setTarget() above only ever runs on the state transition into FIGHT, i.e. on whichever hit
			// happened to land FIRST. Every later hit from a DIFFERENT attacker (the normal case with a
			// host plus companion bots all landing hits) never re-evaluated who to actually fight - the
			// Npc just kept swinging at whoever tagged it first for the rest of the engagement, no matter
			// who was actually doing the damage since. If a bot's hit happened to be first, the host's own
			// attacks went completely unanswered for the whole fight. Re-targeting to whoever is now the
			// most-hated attacker (mirrors the same getMostHated()-driven re-evaluation AttackManager.
			// targetTooFar() already does when the CURRENT target wanders out of range) fixes that without
			// touching the normal single-attacker case. Confirmed live: "the currently targeted mob is
			// suffering from the issue. Just will not target me" - its first hit had come from a bot.
			else if (!npcAI.getOwner().isTargeting(creature.getObjectId())
				&& npcAI.getOwner().getAggroList().isMostHated(creature)) {
				log.info("[aggro] npc={} objId={} onAttack() from {} -> already FIGHT, retargeting (was targeting {})",
					npcAI.getOwner().getName(), npcAI.getOwner().getObjectId(), creature.getName(),
					npcAI.getOwner().getTarget() != null ? npcAI.getOwner().getTarget().getName() : "null");
				npcAI.getOwner().setTarget(creature);
			}
			else {
				log.info("[aggro] npc={} objId={} onAttack() from {} -> already FIGHT, no retarget (currently targeting {}, state={})",
					npcAI.getOwner().getName(), npcAI.getOwner().getObjectId(), creature.getName(),
					npcAI.getOwner().getTarget() != null ? npcAI.getOwner().getTarget().getName() : "null", npcAI.getState());
			}
		}
	}

	/**
	 * @param npcAI
	 */
	public static void onForcedAttack(NpcAI2 npcAI) {
		onAttack(npcAI, (Creature) npcAI.getOwner().getTarget());
	}

	/**
	 * @param npcAI
	 */
	public static void onAttackComplete(NpcAI2 npcAI) {
		if (npcAI.isLogging()) {
			AI2Logger.info(npcAI, "onAttackComplete: " + npcAI.getOwner().getGameStats().getLastAttackTimeDelta());
		}
		npcAI.getOwner().getGameStats().renewLastAttackTime();
		AttackManager.scheduleNextAttack(npcAI);
	}

	/**
	 * @param npcAI
	 */
	public static void onFinishAttack(NpcAI2 npcAI) {
		if (npcAI.isLogging()) {
			AI2Logger.info(npcAI, "onFinishAttack");
		}
		Npc npc = npcAI.getOwner();
		EmoteManager.emoteStopAttacking(npc);
		npc.getLifeStats().startResting();
		npc.getAggroList().clear();
		if (npcAI.poll(AIQuestion.CAN_SHOUT))
			ShoutEventHandler.onAttackEnd(npcAI);
		npc.setTarget(null);
		npc.setSkillNumber(0);
	}
}
