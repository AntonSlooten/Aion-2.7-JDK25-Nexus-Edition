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

public class CompanionConfig {

	/**
	 * Maximum number of companion bots a single player may have summoned at once. Clamped at use to
	 * never exceed the engine's real 6-member group cap minus the host's own slot.
	 */
	@Property(key = "gameserver.companion.maxbots", defaultValue = "5")
	public static int MAX_BOTS_PER_PLAYER;

	/**
	 * Seconds a dead companion bot waits before self-reviving.
	 */
	@Property(key = "gameserver.companion.revivedelay", defaultValue = "8")
	public static int BOT_REVIVE_DELAY;

	/**
	 * HP percent threshold below which a companion bot will prioritize healing over attacking.
	 */
	@Property(key = "gameserver.companion.healhpthreshold", defaultValue = "50")
	public static int BOT_HEAL_HP_THRESHOLD;

	/**
	 * HP percent threshold below which a companion bot will top a group member back up while out of
	 * combat, even though they're not hurt enough to trigger BOT_HEAL_HP_THRESHOLD's urgent-heal
	 * priority. Deliberately below 100 (not just "not full") so a trivial 1-2% ding doesn't burn a
	 * cast/MP for no real benefit.
	 */
	@Property(key = "gameserver.companion.idlehealhpthreshold", defaultValue = "95")
	public static int BOT_IDLE_HEAL_HP_THRESHOLD;

	/**
	 * Milliseconds between companion bot AI decision ticks.
	 */
	@Property(key = "gameserver.companion.aithinkinterval", defaultValue = "750")
	public static int AI_THINK_INTERVAL;
}
