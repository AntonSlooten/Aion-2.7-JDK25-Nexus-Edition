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
package com.aionemu.gameserver.command.player;

import com.aionemu.gameserver.command.BaseCommand;
import com.aionemu.gameserver.configs.main.CompanionConfig;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.BotPlayer;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.player.CompanionService;
import com.aionemu.gameserver.services.player.PlayerEnterWorldService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;

/**
 * Lets a player spawn their own other account characters as AI-controlled companions:
 * .companion list | summon &lt;name&gt; | summonall | dismiss &lt;name&gt; | dismissall.
 * Only ever resolves target characters through the invoking player's own {@link Account} - never
 * from a raw object id - so a player can't summon another account's character.
 */
public class CmdCompanion extends BaseCommand {

	@Override
	public void execute(Player player, String... params) {
		if (params.length == 0) {
			showUsage(player);
			return;
		}

		String sub = params[0];
		if (sub.equalsIgnoreCase("list")) {
			list(player);
		}
		else if (sub.equalsIgnoreCase("summon") && params.length > 1) {
			summon(player, getEndString(params, 1));
		}
		else if (sub.equalsIgnoreCase("summonall")) {
			summonAll(player);
		}
		else if (sub.equalsIgnoreCase("dismiss") && params.length > 1) {
			dismiss(player, getEndString(params, 1));
		}
		else if (sub.equalsIgnoreCase("dismissall")) {
			CompanionService.dismissAllBots(player);
			PacketSendUtility.sendMessage(player, "All companions dismissed.");
		}
		else {
			showUsage(player);
		}
	}

	private void showUsage(Player player) {
		PacketSendUtility.sendMessage(player, "Syntax: .companion list | summon <name> | summonall | dismiss <name> | dismissall");
	}

	private void list(Player player) {
		Account account = player.getClientConnection().getAccount();
		PacketSendUtility.sendMessage(player, "Characters on this account:");
		for (PlayerAccountData pad : account.getSortedAccountsList()) {
			if (pad.getPlayerCommonData().getPlayerObjId() == player.getObjectId())
				continue;
			PacketSendUtility.sendMessage(player, " - " + pad.getPlayerCommonData().getName() + " (Lv."
				+ pad.getPlayerCommonData().getLevel() + " " + pad.getPlayerCommonData().getPlayerClass() + ")");
		}
	}

	private void summon(Player player, String name) {
		Account account = player.getClientConnection().getAccount();
		PlayerAccountData target = findByName(account, name);
		if (target == null) {
			PacketSendUtility.sendMessage(player, "No character named " + name + " on your account.");
			return;
		}
		trySummon(player, account, target);
	}

	private void summonAll(Player player) {
		Account account = player.getClientConnection().getAccount();
		for (PlayerAccountData pad : account.getSortedAccountsList()) {
			if (pad.getPlayerCommonData().getPlayerObjId() == player.getObjectId())
				continue;
			if (!canSummonMore(player))
				break;
			trySummon(player, account, pad);
		}
	}

	private boolean canSummonMore(Player player) {
		int cap = Math.min(CompanionConfig.MAX_BOTS_PER_PLAYER, 5);
		return player.getBots().size() < cap;
	}

	private void trySummon(Player player, Account account, PlayerAccountData target) {
		int objId = target.getPlayerCommonData().getPlayerObjId();

		if (objId == player.getObjectId())
			return;

		if (!canSummonMore(player)) {
			PacketSendUtility.sendMessage(player, "You already have the maximum number of companions summoned.");
			return;
		}

		if (World.getInstance().findPlayer(objId) != null) {
			PacketSendUtility.sendMessage(player, target.getPlayerCommonData().getName() + " is already active.");
			return;
		}

		BotPlayer bot = (BotPlayer) PlayerService.getBotPlayer(objId, account);
		bot.setHostObjectId(player.getObjectId());
		player.addBot(bot);
		PlayerEnterWorldService.botEnterWorld(bot, player);
		PacketSendUtility.sendMessage(player, target.getPlayerCommonData().getName() + " has joined you.");
	}

	private void dismiss(Player player, String name) {
		for (Player bot : player.getBots()) {
			if (bot.getName().equalsIgnoreCase(name)) {
				CompanionService.dismissBot(player, (BotPlayer) bot);
				PacketSendUtility.sendMessage(player, name + " has been dismissed.");
				return;
			}
		}
		PacketSendUtility.sendMessage(player, "No active companion named " + name + ".");
	}

	private PlayerAccountData findByName(Account account, String name) {
		for (PlayerAccountData pad : account.getSortedAccountsList()) {
			if (pad.getPlayerCommonData().getName().equalsIgnoreCase(name))
				return pad;
		}
		return null;
	}
}
