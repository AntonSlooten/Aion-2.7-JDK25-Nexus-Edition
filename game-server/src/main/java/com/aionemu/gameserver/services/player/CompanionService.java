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
package com.aionemu.gameserver.services.player;

import java.util.ArrayList;
import java.util.List;

import com.aionemu.gameserver.ai2.playerbot.PlayerBotAI;
import com.aionemu.gameserver.ai2.playerbot.PlayerBotAITaskManager;
import com.aionemu.gameserver.model.gameobjects.player.BotPlayer;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team2.common.legacy.LootGroupRules;
import com.aionemu.gameserver.model.team2.common.legacy.LootRuleType;
import com.aionemu.gameserver.model.team2.group.PlayerGroup;
import com.aionemu.gameserver.model.team2.group.PlayerGroupService;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;

/**
 * Owns the companion bot lifecycle: grouping a freshly spawned bot with its host, and tearing a bot
 * back down (group removal, AI/move deregistration, despawn, persistence) on dismissal or when the
 * host leaves the game.
 */
public class CompanionService {

	private CompanionService() {
	}

	/**
	 * Adds a companion bot to the host's group, creating one if the host isn't already in one. Bypasses
	 * the normal invite/response dialog - a connectionless bot can't answer it - the same way the
	 * engine's own group-accept callback adds a player once accepted.
	 */
	public static void groupBot(Player host, BotPlayer bot) {
		PlayerGroup group = host.getPlayerGroup2();
		if (group == null) {
			group = PlayerGroupService.createGroup(host, bot);
		}
		else if (group.isFull()) {
			PacketSendUtility.sendMessage(host, bot.getName() + " could not join the group: it is full.");
			return;
		}
		else {
			PlayerGroupService.addPlayer(group, bot);
		}
		useFreeForAllLoot(group);
	}

	/**
	 * Round-robin loot (the engine default - see LootGroupRules' no-arg constructor) means everyone,
	 * bots included, takes a turn "owning" common drops in rotation - annoying with a bot in the group,
	 * since a turn landing on a bot just wastes a cycle on something that will never meaningfully use it.
	 * Free-for-all removes the turn-order concept entirely. Left otherwise untouched (same autodistribution
	 * mode and per-quality roll thresholds the group already had) - this only changes *whose turn it is*,
	 * not whether higher-quality drops still prompt a roll; DropService.canDistribute() separately makes
	 * sure a bot never blocks on one of those if it does happen (see the BotPlayer auto-skip there).
	 * Confirmed requested live: "can we automatically set the group distribution settings to
	 * 'free-for-all' ... ignore bots [during rolling]".
	 */
	private static void useFreeForAllLoot(PlayerGroup group) {
		LootGroupRules current = group.getLootGroupRules();
		if (current.getLootRule() == LootRuleType.FREEFORALL)
			return;
		LootGroupRules freeForAll = new LootGroupRules(LootRuleType.FREEFORALL, current.getAutodistribution(),
			current.getCommonItemAbove(), current.getSuperiorItemAbove(), current.getHeroicItemAbove(),
			current.getFabledItemAbove(), current.getEthernalItemAbove(), current.getMisc());
		PlayerGroupService.changeGroupRules(group, freeForAll);
	}

	public static void dismissBot(Player host, BotPlayer bot) {
		if (bot.getPlayerGroup2() != null)
			PlayerGroupService.removePlayer(bot);

		if (bot.getAi2() instanceof PlayerBotAI)
			PlayerBotAITaskManager.getInstance().removeBot((PlayerBotAI) bot.getAi2());

		bot.getMoveController().abortMove();
		World.getInstance().despawn(bot);
		bot.getController().delete();
		PlayerService.storePlayer(bot);

		host.removeBot(bot);
	}

	public static void dismissAllBots(Player host) {
		List<Player> bots = new ArrayList<Player>(host.getBots());
		for (Player bot : bots) {
			if (bot instanceof BotPlayer)
				dismissBot(host, (BotPlayer) bot);
		}
	}

	/**
	 * Moves every grouped bot to the host's current location by despawning and respawning them there -
	 * the same World primitives PlayerEnterWorldService.botEnterWorld() uses for the initial summon, and
	 * the same pattern TeleportService.changePosition() already uses to bring the host's own Pet along
	 * on a teleport. A bot has no client to receive a teleport packet or fly along under its own power,
	 * so without this it would simply be left behind at the old location whenever the host teleports or
	 * lands from a flight transport. Called synchronously from those two choke points (TeleportService.
	 * changePosition(), PlayerController.onFlyTeleportEnd()) so relocation completes well before the
	 * bot's own periodic think() tick could notice the host missing and go wandering off trying to close
	 * an impossible - or cross-world - distance; per the user's own framing, bots should "stay where
	 * they are until this is achieved" rather than attempt to chase.
	 */
	public static void relocateBots(Player host) {
		for (Player bot : host.getBots()) {
			if (!(bot instanceof BotPlayer) || !bot.isSpawned())
				continue;
			BotPlayer botPlayer = (BotPlayer) bot;
			botPlayer.getMoveController().abortMove();
			World world = World.getInstance();
			world.despawn(botPlayer);
			world.setPosition(botPlayer, host.getWorldId(), host.getInstanceId(), host.getX(), host.getY(), host.getZ(),
				host.getHeading());
			world.spawn(botPlayer);
			// Mirrors the fix in PlayerEnterWorldService.botEnterWorld(): spawning re-applies the 60s
			// zone-entry BLINKING protection (PlayerController.onBeforeSpawn() -> startProtectionActiveTask()),
			// which a real player clears almost instantly on their first move/skill packet but a bot
			// never sends - left alone here it would reproduce the exact "bot invisible to Npc.canSee()
			// for a full minute after every relocation" bug all over again on every single teleport.
			botPlayer.getController().stopProtectionActiveTask();
		}
	}
}
