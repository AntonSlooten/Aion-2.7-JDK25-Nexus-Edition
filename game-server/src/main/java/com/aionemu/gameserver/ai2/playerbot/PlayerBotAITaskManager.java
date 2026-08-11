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

import javolution.util.FastMap;

import com.aionemu.gameserver.configs.main.CompanionConfig;
import com.aionemu.gameserver.taskmanager.AbstractPeriodicTaskManager;

/**
 * Periodic driver for {@link PlayerBotAI#think()}. A companion bot Player never generates the
 * Npc-only aggro/spawn events that normally trigger NPC AI think()-calls reactively, so a bot's
 * decision loop needs an explicit periodic tick instead - this manager is that tick, mirroring
 * {@link com.aionemu.gameserver.taskmanager.tasks.MoveTaskManager}'s pattern. All actual decision
 * logic lives in PlayerBotAI.think() itself; this class is only a dispatcher.
 */
public class PlayerBotAITaskManager extends AbstractPeriodicTaskManager {

	private final FastMap<Integer, PlayerBotAI> bots = new FastMap<Integer, PlayerBotAI>().shared();

	private PlayerBotAITaskManager() {
		super(CompanionConfig.AI_THINK_INTERVAL);
	}

	public void addBot(PlayerBotAI ai) {
		bots.put(ai.getObjectId(), ai);
	}

	public void removeBot(PlayerBotAI ai) {
		bots.remove(ai.getObjectId());
	}

	@Override
	public void run() {
		// The per-bot try/catch below only protects individual think() calls. It does NOT cover the
		// loop machinery itself (bots.head()/tail()/getNext()) - if the shared FastMap gets structurally
		// mutated by addBot()/removeBot() on another thread while this iterates (entirely possible: a
		// bot getting summoned/dismissed doesn't pause AI ticking), anything escaping THAT would still
		// go uncaught out of run(), and scheduleAtFixedRate silently and PERMANENTLY cancels this whole
		// periodic task the instant that happens - every bot's movement, skills, and flight-following
		// just stop dead with zero warnings anywhere. Confirmed live: all 3 bots' logging (think(),
		// syncFlight, buffs) stopped in the same instant mid-session while every other periodic task
		// (DebugService's snapshot) kept ticking fine - the exact signature of this class of bug, not a
		// per-bot think() failure (those already log a warning and don't explain a total silent stop).
		try {
			for (FastMap.Entry<Integer, PlayerBotAI> e = bots.head(), end = bots.tail(); (e = e.getNext()) != end;) {
				try {
					e.getValue().think();
				}
				catch (Throwable ex) {
					log.warn("[PlayerBotAITaskManager] think() failed for bot " + e.getKey(), ex);
				}
			}
		}
		catch (Throwable ex) {
			log.warn("[PlayerBotAITaskManager] run() failed", ex);
		}
	}

	public static PlayerBotAITaskManager getInstance() {
		return SingletonHolder.INSTANCE;
	}

	private static final class SingletonHolder {

		private static final PlayerBotAITaskManager INSTANCE = new PlayerBotAITaskManager();
	}
}
