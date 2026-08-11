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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.aionemu.gameserver.command.BaseCommand;
import com.aionemu.gameserver.configs.main.CompanionConfig;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.BotPlayer;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemSlot;
import com.aionemu.gameserver.model.templates.item.ArmorType;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.network.aion.serverpackets.SM_UPDATE_PLAYER_APPEARANCE;
import com.aionemu.gameserver.network.aion.serverpackets.SM_VIEW_PLAYER_DETAILS;
import com.aionemu.gameserver.services.player.CompanionService;
import com.aionemu.gameserver.services.player.PlayerEnterWorldService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.utils.ChatUtil;
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

		String sub = normalizeSub(params[0]);
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
		else if (sub.equalsIgnoreCase("show") && params.length == 2) {
			show(player, params[1]);
		}
		else if (sub.equalsIgnoreCase("equip") && params.length == 3) {
			equip(player, params[1], params[2]);
		}
		else if (sub.equalsIgnoreCase("equipother") && params.length == 3) {
			equipOther(player, params[1], params[2]);
		}
		else {
			showUsage(player);
		}
	}

	private void showUsage(Player player) {
		PacketSendUtility.sendMessage(player, "Syntax: .companion list | summon <name> | summonall | dismiss <name> | dismissall"
			+ " | show <name> | equip <name> <id> | equipother <name> <id>");
		PacketSendUtility.sendMessage(player, "Short forms: .c l | s <name> | sa | d <name> | da | sh <name> | e <name> <id> | eo <name> <id>");
	}

	/**
	 * Short first-letter forms for every subcommand, spelled out where the bare first letter would
	 * collide (summon/summonall/show all start with 's'; dismiss/dismissall both 'd'; equip/equipother
	 * both 'e'). Falls through to the input unchanged for anything else, so the long forms below keep
	 * working exactly as before. Requested live: "possibly the first letter of each as well as the long
	 * form... you end up typing it a bit."
	 */
	private static String normalizeSub(String sub) {
		switch (sub.toLowerCase()) {
			case "l":
				return "list";
			case "s":
				return "summon";
			case "sa":
				return "summonall";
			case "d":
				return "dismiss";
			case "da":
				return "dismissall";
			case "sh":
				return "show";
			case "e":
				return "equip";
			case "eo":
				return "equipother";
			default:
				return sub;
		}
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

	/**
	 * Two independent caps: the configured max bots per player, AND the engine's real 6-member group
	 * hard cap - which matters whenever the host is already grouped with real players before summoning
	 * any bots. The bot count alone isn't enough: a host in a 3-member human group with 0 bots summoned
	 * would previously pass a "bots.size() &lt; 5" check and get a bot fully spawned into the world
	 * (visible, AI-active, wandering) only to then silently fail to actually join the group (see
	 * CompanionService.groupBot()'s own group.isFull() check, which fires too late - after the bot
	 * already exists) - leaving an orphaned, ungrouped bot with none of the XP-sharing/loot benefits.
	 * Requested live: "groups can only have 6 people... if there are people in the group, take whatever
	 * to fill the group."
	 */
	private boolean canSummonMore(Player player) {
		int cap = Math.min(CompanionConfig.MAX_BOTS_PER_PLAYER, 5);
		if (player.getBots().size() >= cap)
			return false;
		int currentGroupSize = player.isInGroup2() ? player.getPlayerGroup2().size() : 1;
		return currentGroupSize < 6;
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

	/**
	 * Lists what a companion is currently wearing, then its full inventory grouped by equip category
	 * (weapon/ring/earring/armor/etc, everything else last). Each item is written via {@link ChatUtil#item}
	 * - the same real, client-recognized "[item: id]" chat-link format the admin //playerinfo command
	 * already uses to list a player's inventory - so it renders as a clickable link with the real item
	 * tooltip instead of bare text. The trailing "[id N]" is a SEPARATE number: the item's unique object
	 * id, not its template id - that's what {@link #equip} and {@link #equipOther} expect, since a bot
	 * can carry two instances of the same gear template and only the object id disambiguates which one
	 * gets equipped. Requested live: "You can physically link items in the chat, we probably want to do
	 * the same with the listed items, so I can actually view them."
	 */
	private void show(Player player, String name) {
		BotPlayer bot = findBot(player, name);
		if (bot == null) {
			PacketSendUtility.sendMessage(player, "No active companion named " + name + ".");
			return;
		}

		PacketSendUtility.sendMessage(player, "=== " + bot.getName() + " is wearing ===");
		for (Item item : bot.getEquipment().getEquippedItemsWithoutStigma()) {
			if (!isGear(item.getItemTemplate()))
				continue;
			PacketSendUtility.sendMessage(player, " - " + enchantPrefix(item) + ChatUtil.item(item.getItemId()) + " [id " + item.getObjectId() + "]");
		}

		Map<String, List<Item>> byCategory = new TreeMap<String, List<Item>>();
		for (Item item : bot.getInventory().getItems()) {
			if (!isGear(item.getItemTemplate()))
				continue;
			String category = categorize(item.getItemTemplate());
			List<Item> items = byCategory.get(category);
			if (items == null) {
				items = new ArrayList<Item>();
				byCategory.put(category, items);
			}
			items.add(item);
		}

		PacketSendUtility.sendMessage(player, "=== " + bot.getName() + "'s inventory ===");
		for (Map.Entry<String, List<Item>> entry : byCategory.entrySet()) {
			PacketSendUtility.sendMessage(player, "-- " + entry.getKey() + " --");
			for (Item item : entry.getValue()) {
				String countSuffix = item.getItemCount() > 1 ? " x" + item.getItemCount() : "";
				PacketSendUtility.sendMessage(player, " " + enchantPrefix(item) + ChatUtil.item(item.getItemId()) + countSuffix + " [id " + item.getObjectId() + "]");
			}
		}
	}

	/**
	 * ChatUtil.item()'s "[item: id]" link only ever encodes the bare template id - every one of the
	 * ~30 other places in this codebase that construct one does the same, and the real client-side rich
	 * link format (the one an actual shift-click produces, which DOES carry enchant/socket data) isn't
	 * recorded anywhere in this codebase to reproduce. So the tooltip a linked item shows is always the
	 * base item, even though the actual instance (correctly reflected by /inspect, which sends the real
	 * Item object rather than a text link) may be enchanted. This at least surfaces the enchant level as
	 * plain text alongside the link so the two don't look identical in chat. Requested live: "I have
	 * enchanged an item, and the linked item is the base item. however what is returned in /inspect is
	 * the correct item."
	 */
	private String enchantPrefix(Item item) {
		return item.getEnchantLevel() > 0 ? "+" + item.getEnchantLevel() + " " : "";
	}

	/**
	 * Restricts both the "is wearing" and inventory listings to actual gear (weapons/armor/jewelry/
	 * wings) - excludes stigmas (equip type STIGMA, category="STIGMA" in the item data, e.g. "Advanced
	 * Dual-Wielding") as well as potions, scrolls, quest items, and every other consumable/material,
	 * none of which .equip/.equipother can do anything with anyway. Requested live: "the c show
	 * <character> also shows equipped skills such as 'advanced dual wielding'... we probably only want
	 * to list the armor/weapons, not the potions, consumables, and everything else not related."
	 */
	private boolean isGear(ItemTemplate template) {
		return template.isWeapon() || template.isArmor();
	}

	private String categorize(ItemTemplate template) {
		if (template.isWeapon())
			return "Weapon";
		if (template.isArmor()) {
			ArmorType armorType = template.getArmorType();
			if (armorType == null) {
				int mask = template.getItemSlot();
				if ((mask & (ItemSlot.RING_LEFT.getSlotIdMask() | ItemSlot.RING_RIGHT.getSlotIdMask())) != 0)
					return "Ring";
				if ((mask & (ItemSlot.EARRINGS_LEFT.getSlotIdMask() | ItemSlot.EARRINGS_RIGHT.getSlotIdMask())) != 0)
					return "Earring";
				if (mask == ItemSlot.NECKLACE.getSlotIdMask())
					return "Necklace";
				if (mask == ItemSlot.HELMET.getSlotIdMask())
					return "Helmet";
				if (mask == ItemSlot.WINGS.getSlotIdMask())
					return "Wings";
				return "Accessory";
			}
			switch (armorType) {
				case SHIELD:
					return "Shield";
				case SHARD:
					return "Power Shard";
				case ARROW:
					return "Arrow";
				default:
					return "Armor";
			}
		}
		return "Other";
	}

	/**
	 * Equips into whichever slot {@link com.aionemu.gameserver.model.gameobjects.player.Equipment#equipItem}
	 * picks by default (first free, else the pair's primary slot).
	 */
	private void equip(Player player, String name, String itemIdParam) {
		BotPlayer bot = findBot(player, name);
		if (bot == null) {
			PacketSendUtility.sendMessage(player, "No active companion named " + name + ".");
			return;
		}
		Item item = resolveItem(player, bot, itemIdParam);
		if (item == null)
			return;

		Item equipped = bot.getEquipment().equipItem(item.getObjectId(), item.getItemTemplate().getItemSlot());
		if (equipped == null) {
			String reason = bot.getEquipment().getLastEquipFailureReason();
			PacketSendUtility.sendMessage(player, name + " can't equip " + item.getItemTemplate().getName()
				+ (reason != null ? " - " + reason + "." : "."));
		}
		else {
			PacketSendUtility.sendMessage(player, name + " equipped " + item.getItemTemplate().getName() + ".");
			refreshInspectWindow(player, bot);
			broadcastAppearance(bot);
		}
	}

	/**
	 * Forces the item into the *second* slot of its combo pair - the only way to gear up, say, a ring
	 * in the off slot when the primary ring slot is already occupied by something you don't want to
	 * disturb. Requested live: "the equipother has to take into account rings, earrings, and weapons."
	 */
	private void equipOther(Player player, String name, String itemIdParam) {
		BotPlayer bot = findBot(player, name);
		if (bot == null) {
			PacketSendUtility.sendMessage(player, "No active companion named " + name + ".");
			return;
		}
		Item item = resolveItem(player, bot, itemIdParam);
		if (item == null)
			return;

		Item equipped = bot.getEquipment().equipItemToSecondarySlot(item.getObjectId());
		if (equipped == null) {
			String reason = bot.getEquipment().getLastEquipFailureReason();
			PacketSendUtility.sendMessage(player, name + " can't equip " + item.getItemTemplate().getName()
				+ (reason != null ? " - " + reason + "." : "."));
		}
		else {
			PacketSendUtility.sendMessage(player, name + " equipped " + item.getItemTemplate().getName() + " to the other slot.");
			refreshInspectWindow(player, bot);
			broadcastAppearance(bot);
		}
	}

	/**
	 * For a real player, CM_EQUIP_ITEM.runImpl() is the one that broadcasts SM_UPDATE_PLAYER_APPEARANCE
	 * after a successful equip - that call lives in the client-packet handler, not inside Equipment
	 * itself, since a bot's .companion equip/equipother goes straight through Equipment and never passes
	 * through that handler, so nearby clients (including the host's own) were never told the bot's model
	 * changed at all - it kept rendering, and attacking with, whatever it looked like before. Requested
	 * live: "the character model is not updated... he still attacks with the old weapon."
	 */
	private void broadcastAppearance(BotPlayer bot) {
		PacketSendUtility.broadcastPacket(bot,
			new SM_UPDATE_PLAYER_APPEARANCE(bot.getObjectId(), bot.getEquipment().getEquippedItemsWithoutStigma()), true);
	}

	/**
	 * /inspect is a one-shot request/response, not a live subscription - nothing in the client/server
	 * protocol re-sends SM_VIEW_PLAYER_DETAILS to anyone after the target's gear changes, so a host with
	 * the bot's inspect window already open wouldn't otherwise see the swap take effect until they
	 * closed and reopened it. Since we're the ones causing the change, we already know exactly when to
	 * push a fresh snapshot - no polling needed. Requested live: "afaik /inspect only runs once... I
	 * suspect we need to continually update it in the background?"
	 */
	private void refreshInspectWindow(Player host, BotPlayer bot) {
		PacketSendUtility.sendPacket(host, new SM_VIEW_PLAYER_DETAILS(bot.getEquipment().getEquippedItemsWithoutStigma(), bot));
	}

	private Item resolveItem(Player player, BotPlayer bot, String itemIdParam) {
		int objectId;
		try {
			objectId = Integer.parseInt(itemIdParam);
		}
		catch (NumberFormatException e) {
			PacketSendUtility.sendMessage(player, "Invalid item id: " + itemIdParam);
			return null;
		}
		Item item = bot.getInventory().getItemByObjId(objectId);
		if (item == null)
			PacketSendUtility.sendMessage(player, bot.getName() + " isn't carrying that item. Use .companion show " + bot.getName() + " to list ids.");
		return item;
	}

	private BotPlayer findBot(Player player, String name) {
		for (Player bot : player.getBots()) {
			if (bot.getName().equalsIgnoreCase(name))
				return (BotPlayer) bot;
		}
		return null;
	}

	private PlayerAccountData findByName(Account account, String name) {
		for (PlayerAccountData pad : account.getSortedAccountsList()) {
			if (pad.getPlayerCommonData().getName().equalsIgnoreCase(name))
				return pad;
		}
		return null;
	}
}
