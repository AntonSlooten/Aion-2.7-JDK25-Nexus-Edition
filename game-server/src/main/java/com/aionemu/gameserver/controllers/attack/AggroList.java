/*
 * This file is part of aion-unique <aion-unique.org>.
 *
 *  aion-unique is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  aion-unique is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with aion-unique.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.controllers.attack;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javolution.util.FastMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.callbacks.Callback;
import com.aionemu.commons.callbacks.CallbackResult;
import com.aionemu.commons.callbacks.metadata.ObjectCallback;
import com.aionemu.gameserver.ai2.event.AIEventType;
import com.aionemu.gameserver.configs.main.DebugConfig;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.AionObject;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team2.group.PlayerGroup;
import com.aionemu.gameserver.world.knownlist.Visitor;

/**
 * @author ATracer, KKnD
 */
@SuppressWarnings("rawtypes")
public class AggroList {

	private static final Logger log = LoggerFactory.getLogger(AggroList.class);

	protected final Creature owner;

	private FastMap<Integer, AggroInfo> aggroList = new FastMap<Integer, AggroInfo>().shared();

	public AggroList(Creature owner) {
		this.owner = owner;
	}

	/**
	 * Only add damage from enemies. (Verify this includes summons, traps, pets, and excludes fall damage.)
	 * 
	 * @param creature
	 * @param damage
	 */
	@ObjectCallback(AddDamageValueCallback.class)
	public void addDamage(Creature creature, int damage) {
		if (!isAware(creature)) {
			// Diagnostic for "monster gets hit but doesn't recognize/retaliate against the attacker" -
			// this is the VERY FIRST gate on the whole aggro path: if isAware() is false, damage still
			// lands (reduceHp() runs regardless, in CreatureController.onAttack()) but NOTHING here
			// happens - no hate, no AIEventType.ATTACK, no state change. isAware() requires EITHER
			// creature.isEnemy(owner) OR a tribe-hostile relation; logging both inputs plus damage dealt
			// to see which one is failing. Requested live: "it did not recognize the attacker, and
			// regenned quickly."
			if (DebugConfig.NPC_COMBAT_LOGGING && owner instanceof Npc && creature != null)
				log.info("[aggro] npc={} objId={} templateId={} NOT AWARE of attacker={} dmg={} - isEnemy={} ownerTribe={} attackerTribe={} hostileTribes={}",
					owner.getName(), owner.getObjectId(), ((Npc) owner).getNpcId(), creature.getName(), damage,
					creature.isEnemy(owner), owner.getTribe(), creature.getTribe(),
					DataManager.TRIBE_RELATIONS_DATA.isHostileRelation(owner.getTribe(), creature.getTribe()));
			return;
		}

		AggroInfo ai = getAggroInfo(creature);
		ai.addDamage(damage);
		/**
		 * For now we add hate equal to each damage received Additionally there will be broadcast of extra hate
		 */
		// A resisted/missed/grazed hit still deals real (0) damage here, but MUST still register at
		// least 1 hate: GeneralNpcAI2.chooseAttackIntention() - invoked synchronously from this same
		// call via the ATTACK event fired below, on literally the FIRST attack that transitions the Npc
		// into FIGHT - immediately returns FINISH_ATTACK whenever getMostHated() is null, and
		// getMostHated() requires hate > 0 to consider an aggro entry at all (a zero-hate entry is
		// indistinguishable from "nothing here yet"). Without this, an opening hit that happens to deal
		// zero net damage makes the Npc "give up" instantly - before ever swinging back - clearing its
		// aggro and starting NpcLifeStats' 25%-per-tick "resting" regen while still visibly under attack.
		// Far likelier with a large level gap (steep miss/resist chance) and/or several simultaneous
		// attackers (more chances for whichever hit lands first to be the unlucky one) - confirmed live
		// with a level 6 companion bot's opening hit on a much higher-level target.
		ai.addHate(Math.max(damage, 1));

		// TODO move out to controller
		owner.getAi2().onCreatureEvent(AIEventType.ATTACK, creature);
	}

	public void addHate(final Creature creature, int hate) {
		addHate(creature, hate, true);
	}

	/**
	 * Extra hate that is received from using non-damange skill effects
	 */
	public void addHate(final Creature creature, int hate, boolean socialCall) {
		if (!isAware(creature))
			return;

		addHateValue(creature, hate);
		if (socialCall)
			owner.getKnownList().doOnAllNpcs(new Visitor<Npc>() {

				@Override
				public void visit(Npc object) {
					object.getAi2().onCreatureEvent(AIEventType.CREATURE_ATTACKED, owner);
				}
			});
	}

	/**
	 * start hating creature by adding 1 hate value
	 */
	public void startHate(final Creature creature) {
		addHateValue(creature, 1);
	}

	protected void addHateValue(final Creature creature, int hate) {
		AggroInfo ai = getAggroInfo(creature);
		ai.addHate(hate);

		// TODO move out to controller
		owner.getAi2().onCreatureEvent(AIEventType.ATTACK, creature);
	}

	/**
	 * @return player/group/alliance with most damage.
	 */
	public AionObject getMostDamage() {
		AionObject mostDamage = null;
		int maxDamage = 0;

		for (AggroInfo ai : getFinalDamageList(true)) {
			if (ai.getAttacker() == null)
				continue;

			if (ai.getDamage() > maxDamage) {
				mostDamage = ai.getAttacker();
				maxDamage = ai.getDamage();
			}
		}

		return mostDamage;
	}

	public Race getPlayerWinnerRace() {
		AionObject winner = getMostDamage();
		if (winner instanceof PlayerGroup) {
			return ((PlayerGroup) winner).getRace();
		}
		else if (winner instanceof Player)
			return ((Player) winner).getRace();
		return null;
	}

	/**
	 * @return player with most damage
	 */
	public Player getMostPlayerDamage() {
		if (aggroList.isEmpty())
			return null;

		Player mostDamage = null;
		int maxDamage = 0;

		// Use final damage list to get pet damage as well.
		for (AggroInfo ai : this.getFinalDamageList(false)) {
			if (ai.getDamage() > maxDamage) {
				mostDamage = (Player) ai.getAttacker();
				maxDamage = ai.getDamage();
			}
		}

		return mostDamage;
	}

	/**
	 * @return player with most damage
	 */
	public Player getMostPlayerDamageOfMembers(Collection<Player> players) {
		if (aggroList.isEmpty())
			return null;

		Player mostDamage = null;
		int maxDamage = 0;

		// Use final damage list to get pet damage as well.
		for (AggroInfo ai : this.getFinalDamageList(false)) {
			if (!players.contains((Player) ai.getAttacker())) {
				continue;
			}
			if (ai.getDamage() > maxDamage) {

				mostDamage = (Player) ai.getAttacker();
				maxDamage = ai.getDamage();
			}
		}

		return mostDamage;
	}

	/**
	 * @return most hated creature
	 */
	public Creature getMostHated() {
		if (aggroList.isEmpty())
			return null;

		Creature mostHated = null;
		int maxHate = 0;

		for (FastMap.Entry<Integer, AggroInfo> e = aggroList.head(), mapEnd = aggroList.tail(); (e = e.getNext()) != mapEnd;) {
			AggroInfo ai = e.getValue();
			if (ai == null)
				continue;

			// aggroList will never contain anything but creatures
			Creature attacker = (Creature) ai.getAttacker();

			boolean dead = attacker.getLifeStats().isAlreadyDead();
			boolean known = owner.getKnownList().knowns(attacker);
			if (dead || !known) {
				// Diagnostic for "monster stops fighting back and stays that way until it dies" - this is
				// the exact gate that zeroes an attacker's hate out of getMostHated() consideration.
				// Logging region-activity state alongside knowns() to confirm/deny whether
				// NpcKnownList.doUpdate()'s region-active gate (which wipes the whole known-list instead
				// of just skipping a refresh when the region isn't considered active) is why a companion
				// bot's presence never registers. Requested live, confirmed deterministic and persistent
				// rather than a one-off timing miss.
				if (DebugConfig.NPC_COMBAT_LOGGING && owner instanceof com.aionemu.gameserver.model.gameobjects.Npc && !dead)
					log.info(
						"[knownlist] npc={} objId={} attacker={} known={} npcRegionActive={} attackerRegionActive={}",
						owner.getName(), owner.getObjectId(), attacker.getName(), known,
						owner.getActiveRegion() != null ? owner.getActiveRegion().isMapRegionActive() : "null-region",
						attacker.getActiveRegion() != null ? attacker.getActiveRegion().isMapRegionActive() : "null-region");
				ai.setHate(0);
			}

			if (ai.getHate() > maxHate) {
				mostHated = attacker;
				maxHate = ai.getHate();
			}
		}

		return mostHated;
	}

	/**
	 * @param creature
	 * @return
	 */
	public boolean isMostHated(Creature creature) {
		if (creature == null || creature.getLifeStats().isAlreadyDead())
			return false;

		Creature mostHated = getMostHated();
		return mostHated != null && mostHated.equals(creature);

	}

	/**
	 * @param creature
	 * @param value
	 */
	public void notifyHate(Creature creature, int value) {
		if (isHating(creature))
			addHate(creature, value);
	}

	/**
	 * @param creature
	 */
	public void stopHating(VisibleObject creature) {
		AggroInfo aggroInfo = aggroList.get(creature.getObjectId());
		if (aggroInfo != null)
			aggroInfo.setHate(0);
	}

	/**
	 * Remove completely creature from aggro list
	 * 
	 * @param creature
	 */
	public void remove(Creature creature) {
		aggroList.remove(creature.getObjectId());
	}

	/**
	 * Clear aggroList
	 */
	public void clear() {
		aggroList.clear();
	}

	/**
	 * @param creature
	 * @return aggroInfo
	 */
	public AggroInfo getAggroInfo(Creature creature) {
		AggroInfo ai = aggroList.get(creature.getObjectId());
		if (ai == null) {
			ai = new AggroInfo(creature);
			aggroList.put(creature.getObjectId(), ai);
		}
		return ai;
	}

	/**
	 * @param creature
	 * @return boolean
	 */
	public boolean isHating(Creature creature) {
		return aggroList.containsKey(creature.getObjectId());
	}

	/**
	 * @return aggro list
	 */
	public Collection<AggroInfo> getList() {
		return aggroList.values();
	}

	/**
	 * @return total damage
	 */
	public int getTotalDamage() {
		int totalDamage = 0;
		for (AggroInfo ai : aggroList.values()) {
			totalDamage += ai.getDamage();
		}
		return totalDamage;
	}
	
	/*public int getCountPlayer(){
		int res = 0;
		for (AggroInfo ai : aggroList.values()) {
			if (ai.getAttacker() instanceof Player){
				res++;
			}
		}
		return res;
	}*/

	/**
	 * Used to get a list of AggroInfo with player/group/alliance damages combined. - Includes only AggroInfo with
	 * PlayerAlliance, PlayerGroup, and Player objects.
	 * 
	 * @return finalDamageList including players/groups/alliances
	 */
	public Collection<AggroInfo> getFinalDamageList(boolean mergeGroupDamage) {
		Map<Integer, AggroInfo> list = new HashMap<Integer, AggroInfo>();

		for (AggroInfo ai : aggroList.values()) {
			if (!(ai.getAttacker() instanceof Creature))
				continue;

			// Check to see if this is a summon, if so add the damage to the group.

			Creature master = ((Creature) ai.getAttacker()).getMaster();

			// A single non-Player-mastered attacker in the raw aggro list (another hostile creature that
			// also happened to tag this same target, a stray trap/effect - anything whose damage doesn't
			// ultimately trace back to a Player) used to abort this ENTIRE method and hand back an empty
			// list, silently wiping every legitimate player's and bot's earned credit for the kill, not
			// just excluding the one foreign entry. That made kill rewards/loot rights fail intermittently
			// depending on what else happened to have hit the same target that fight, not on anything the
			// player or their companions did. Requested live: "if my companions kill it... no one can loot
			// it," then "hmm but not always" once a few different kills were compared.
			if (!(master instanceof Player))
				continue;

			Player player = (Player) master;

			// Don't include damage from players outside the known list.
			if (!owner.getKnownList().knowns(player))
				continue;

			if (mergeGroupDamage) {
				AionObject source;

				if (player.isInTeam()) {
					source = player.getCurrentTeam();
				}
				else {
					source = player;
				}

				if (list.containsKey(source.getObjectId())) {
					list.get(source.getObjectId()).addDamage(ai.getDamage());
				}
				else {
					AggroInfo aggro = new AggroInfo(source);
					aggro.setDamage(ai.getDamage());
					list.put(source.getObjectId(), aggro);
				}
			}
			else if (list.containsKey(player.getObjectId())) {
				// Summon or other assistance
				list.get(player.getObjectId()).addDamage(ai.getDamage());
			}
			else {
				// Create a separate object so we don't taint current list.
				AggroInfo aggro = new AggroInfo(player);
				aggro.addDamage(ai.getDamage());
				list.put(player.getObjectId(), aggro);
			}
		}

		return list.values();
	}

	protected boolean isAware(Creature creature) {
		return creature != null && !creature.getObjectId().equals(owner.getObjectId()) && 
			(creature.isEnemy(owner) || DataManager.TRIBE_RELATIONS_DATA.isHostileRelation(owner.getTribe(), creature.getTribe()));
	}

	public static abstract class AddDamageValueCallback implements Callback<AggroList> {

		@Override
		public final CallbackResult beforeCall(AggroList obj, Object[] args) {
			return CallbackResult.newContinue();
		}

		@Override
		public final CallbackResult afterCall(AggroList obj, Object[] args, Object methodResult) {

			Creature creature = (Creature) args[0];
			Integer damage = (Integer) args[1];

			if (obj.isAware(creature)) {
				onDamageAdded(creature, damage);
			}

			return CallbackResult.newContinue();
		}

		@Override
		public final Class<? extends Callback> getBaseClass() {
			return AddDamageValueCallback.class;
		}

		public abstract void onDamageAdded(Creature creature, int hate);
	}
}
