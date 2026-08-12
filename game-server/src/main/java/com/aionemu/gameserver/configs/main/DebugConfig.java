/*
 * This file is part of aion-emu <aion-emu.com>.
 *
 * aion-emu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aion-emu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with aion-emu. If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

/**
 * Toggles for the verbose diagnostic logging accumulated across many rounds of live debugging on
 * specific engine subsystems - each one is far too noisy (multiple lines per relevant event, some per
 * tick) to leave on for normal play, but worth keeping in the code rather than deleting since they've
 * each already proven useful once and the underlying mechanisms they trace are easy to regress again.
 * Deliberately separate from {@link CompanionConfig#DEBUG_LOGGING} (the companion-bot AI's own flag) -
 * these trace general engine subsystems that happen to have been investigated via bot symptoms, not the
 * bot AI itself. Requested live: "group them by a new couple of general keys... potentially we should
 * add a config file for various debug options."
 */
public class DebugConfig {

	/**
	 * `[aggro]`/`[aistate]`/`[knownlist]`/`[regen]`/`[heal]` - NPC combat AI state and aggro-list tracing
	 * (`AbstractAI`, `AttackEventHandler`, `AggroList`, `Npc.setTarget()`, `CreatureLifeStats`). Root-caused
	 * the "monster stops fighting back and never resumes" and "NPC out-heals bot damage" bugs.
	 */
	@Property(key = "gameserver.debug.npccombat", defaultValue = "false")
	public static boolean NPC_COMBAT_LOGGING;

	/**
	 * `[skilldiag]` - per-condition skill-cast rejection tracing (`Skill.canUseSkill()`, `Conditions.
	 * validate()`), names the exact `Condition` subclass that rejected a cast.
	 */
	@Property(key = "gameserver.debug.skillengine", defaultValue = "false")
	public static boolean SKILL_ENGINE_LOGGING;

	/**
	 * `[PACKETHEALTH]`/`[SNAPSHOT]` - periodic (10s) `DebugService` diagnostics: packet-processor queue
	 * depth/thread count, and a dump of every Player/BotPlayer's connection/spawn/group state. Both stem
	 * from the still-unresolved intermittent server-lag/client-crash investigations. Doesn't cover
	 * `[packetlag]` (the per-slow-packet warning in `commons`' `PacketProcessor`) - that lives in a module
	 * game-server config classes aren't visible to, and is a `log.warn` for a genuine threshold breach
	 * rather than routine tick noise, so it's left always-on rather than gated.
	 */
	@Property(key = "gameserver.debug.network", defaultValue = "false")
	public static boolean NETWORK_LOGGING;
}
