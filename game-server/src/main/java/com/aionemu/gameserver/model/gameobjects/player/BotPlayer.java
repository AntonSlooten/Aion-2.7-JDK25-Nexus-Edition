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
package com.aionemu.gameserver.model.gameobjects.player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.controllers.PlayerController;
import com.aionemu.gameserver.controllers.movement.BotMoveController;
import com.aionemu.gameserver.model.account.Account;

/**
 * A companion bot: a real Player object built from one of the owning account's own characters,
 * spawned without a client connection and driven by PlayerBotAI instead of a packet stream.
 */
public class BotPlayer extends Player {

	/**
	 * Milliseconds a skill is skipped by PlayerBotSkillSelector after useSkill() returns false for it.
	 * Off-cooldown doesn't mean castable - skills can be silently rejected for reasons the selector has
	 * no visibility into (combo-chain preconditions tracked via Player.getChainCategory(), a defensive
	 * counter-skill window, insufficient resources, range/LoS) - without this, a rejected skill looks
	 * identical to a usable one on the very next think() tick and gets retried forever, which is exactly
	 * what was observed live: the same skill failing every ~750ms indefinitely instead of the bot ever
	 * trying something else.
	 */
	private static final long SKILL_FAILURE_RETRY_MS = 4000L;

	private int hostObjectId;
	private final Map<Integer, Long> skillFailureCooldowns = new ConcurrentHashMap<Integer, Long>();

	public BotPlayer(PlayerController controller, PlayerCommonData plCommonData, PlayerAppearance appereance, Account account) {
		super(controller, plCommonData, appereance, account);
		this.moveController = new BotMoveController(this);
		// Bots fly whenever their host does (see PlayerBotAI.think()) and never have a client keeping
		// a flight-energy resource topped up, so exempt them from the normal FP-drain/forced-landing task.
		setUnderNoFPConsum(true);
	}

	public boolean isSkillOnFailureCooldown(int skillId) {
		Long retryAt = skillFailureCooldowns.get(skillId);
		return retryAt != null && retryAt > System.currentTimeMillis();
	}

	public void markSkillFailed(int skillId) {
		skillFailureCooldowns.put(skillId, System.currentTimeMillis() + SKILL_FAILURE_RETRY_MS);
	}

	@Override
	public boolean isOnline() {
		return true;
	}

	@Override
	public boolean isBot() {
		return true;
	}

	public int getHostObjectId() {
		return hostObjectId;
	}

	public void setHostObjectId(int hostObjectId) {
		this.hostObjectId = hostObjectId;
	}
}
