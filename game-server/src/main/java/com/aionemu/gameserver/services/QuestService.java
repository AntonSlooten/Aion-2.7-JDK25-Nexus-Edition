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
package com.aionemu.gameserver.services;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.configs.main.GroupConfig;
import com.aionemu.gameserver.configs.main.MembershipConfig;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.dataholders.QuestsData;
import com.aionemu.gameserver.model.DescriptionId;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.drop.Drop;
import com.aionemu.gameserver.model.drop.DropItem;
import com.aionemu.gameserver.model.gameobjects.DropNpc;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.BotPlayer;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.QuestStateList;
import com.aionemu.gameserver.model.gameobjects.player.RewardType;
import com.aionemu.gameserver.model.gameobjects.player.npcFaction.NpcFaction;
import com.aionemu.gameserver.model.items.ItemId;
import com.aionemu.gameserver.model.skill.PlayerSkillEntry;
import com.aionemu.gameserver.model.team2.common.legacy.LootRuleType;
import com.aionemu.gameserver.model.team2.group.PlayerGroup;
import com.aionemu.gameserver.model.templates.QuestTemplate;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.model.templates.quest.CollectItem;
import com.aionemu.gameserver.model.templates.quest.CollectItems;
import com.aionemu.gameserver.model.templates.quest.HandlerSideDrop;
import com.aionemu.gameserver.model.templates.quest.QuestBonuses;
import com.aionemu.gameserver.model.templates.quest.QuestCategory;
import com.aionemu.gameserver.model.templates.quest.QuestDrop;
import com.aionemu.gameserver.model.templates.quest.QuestItems;
import com.aionemu.gameserver.model.templates.quest.QuestMentorType;
import com.aionemu.gameserver.model.templates.quest.QuestRepeatCycle;
import com.aionemu.gameserver.model.templates.quest.QuestWorkItems;
import com.aionemu.gameserver.model.templates.quest.Rewards;
import com.aionemu.gameserver.model.templates.quest.XMLStartCondition;
import com.aionemu.gameserver.network.aion.serverpackets.SM_LOOT_STATUS;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUEST_ACTION;
import com.aionemu.gameserver.network.aion.serverpackets.SM_SYSTEM_MESSAGE;
import com.aionemu.gameserver.questEngine.QuestEngine;
import com.aionemu.gameserver.questEngine.handlers.HandlerResult;
import com.aionemu.gameserver.questEngine.handlers.models.WorkOrdersData;
import com.aionemu.gameserver.questEngine.handlers.models.XMLQuest;
import com.aionemu.gameserver.questEngine.model.QuestEnv;
import com.aionemu.gameserver.questEngine.model.QuestState;
import com.aionemu.gameserver.questEngine.model.QuestStatus;
import com.aionemu.gameserver.services.abyss.AbyssPointsService;
import com.aionemu.gameserver.services.drop.DropRegistrationService;
import com.aionemu.gameserver.services.item.ItemPacketService.ItemUpdateType;
import com.aionemu.gameserver.services.item.ItemService;
import com.aionemu.gameserver.services.reward.BonusService;
import com.aionemu.gameserver.spawnengine.SpawnEngine;
import com.aionemu.gameserver.utils.MathUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.utils.audit.AuditLogger;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

/**
 * @author Mr. Poke
 * @modified vlog, bobobear, xTz, Rolandas
 */
public final class QuestService {

	static QuestsData questsData = DataManager.QUEST_DATA;
	private static final Logger log = LoggerFactory.getLogger(QuestService.class);
	private static Multimap<Integer, QuestDrop> questDrop = ArrayListMultimap.create();

	public static boolean finishQuest(QuestEnv env) {
		return finishQuest(env, 0);
	}

	public static boolean finishQuest(QuestEnv env, int reward) {
		Player player = env.getPlayer();
		int id = env.getQuestId();
		QuestState qs = player.getQuestStateList().getQuestState(id);
		if (qs == null || qs.getStatus() != QuestStatus.REWARD) {
			return false;
		}
		QuestTemplate template = questsData.getQuestById(id);
		if (template.getCategory() == QuestCategory.MISSION && qs.getCompleteCount() != 0) {
			return false; // prevent repeatable reward because of wrong quest handling
		}
		boolean success;
		if (!template.getExtendedRewards().isEmpty() && qs.getCompleteCount() == template.getMaxRepeatCount() - 1) {
			// This is the last time
			success = giveRewardAndFinish(env, template, true, 0, false);
		}
		else if (!template.getRewards().isEmpty() || !template.getBonus().isEmpty()) {
			success = giveRewardAndFinish(env, template, false, reward, false);
		}
		else {
			success = setFinishingState(env, template, reward);
		}
		if (success) {
			syncQuestCompletionToBots(env, template, reward);
		}
		return success;
	}

	/**
	 * Mirrors a "generic" quest's completion onto every grouped bot that's eligible and hasn't already
	 * completed it, granting the SAME reward (items/kinah/exp/title/AP) rather than just experience -
	 * requested live: "the bot probably wants to be granted the kinah, items and AP from the quest, as
	 * there are times they are reasonable for a leveling character." A bot never actually progresses
	 * through the quest's steps (collecting items, talking to NPCs along the way) - that's deliberately
	 * not tracked at all; the bot's own QuestState is materialized directly into REWARD status right
	 * before reusing giveRewardAndFinish()/setFinishingState() below to grant it, exactly as if it had
	 * just reached the turn-in step itself. Reusing those methods (rather than hand-rolling the grant
	 * logic) means class-specific selectable rewards (template.isUseClassReward()) automatically
	 * resolve against the BOT'S OWN class, not the host's - those methods read env.getPlayer(), and the
	 * env constructed below points at the bot.
	 *
	 * Deliberately excluded, per explicit request: class-restricted quests (getClassPermitted()
	 * non-empty - tied to a specific character's build, not something a differently-classed companion
	 * should "complete" as a side effect) and crafting quests (getCombineSkill() != null - tied to the
	 * bot's own crafting skill progression, which this doesn't touch). Also restricted to
	 * QuestCategory.QUEST (ordinary player-accepted quests) - MISSION/EVENT/TASK/FACTION quests are
	 * driven by different acceptance paths this feature isn't scoped to handle.
	 *
	 * The anti-double-dip rule requested live ("if they have done it already, lets not give them extra
	 * exp") is Player.isCompleteQuest() - a bot's completion history is permanent and per-character, so
	 * cycling which alt is currently "host" can never re-earn the same one-time reward twice.
	 */
	private static void syncQuestCompletionToBots(QuestEnv hostEnv, QuestTemplate template, int reward) {
		Player host = hostEnv.getPlayer();
		if (host.isBot())
			return;
		int id = template.getId();
		if (host.getBots().isEmpty())
			return;
		if (template.getCategory() != QuestCategory.QUEST && template.getCategory() != QuestCategory.MISSION
			&& template.getCategory() != QuestCategory.IMPORTANT) {
			log.info("[questsyncdbg] quest={} skipped: category={}", id, template.getCategory());
			return;
		}
		// "Ascension" (1006 Elyos / 2008 Asmodian, both MISSION category, minlevel 9) is the actual
		// second-class-selection quest - its handler calls ClassChangeService.setClass() directly off
		// whichever dialog option the HOST clicked. Syncing it would silently reassign a bot's class to
		// whatever the host happened to pick, which the bot's own account owner never chose - the one
		// thing that must never be shared here, unlike ordinary MISSION-category story quests. Requested
		// live: "the only one that should really be avoided at all cost is the actual class selection
		// quest - destiny or whatever it's called - that only triggers at level 9."
		if (id == 1006 || id == 2008) {
			log.info("[questsyncdbg] quest={} skipped: class-ascension quest, never synced", id);
			return;
		}
		if (!template.getClassPermitted().isEmpty() || template.getCombineSkill() != null) {
			log.info("[questsyncdbg] quest={} skipped: classPermitted={} combineSkill={}", id,
				template.getClassPermitted(), template.getCombineSkill());
			return;
		}

		for (Player member : host.getBots()) {
			if (!(member instanceof BotPlayer))
				continue;
			BotPlayer bot = (BotPlayer) member;
			if (bot.isCompleteQuest(id)) {
				log.info("[questsyncdbg] quest={} bot={} skipped: already complete", id, bot.getName());
				continue;
			}
			if (template.getRacePermitted() != null && template.getRacePermitted() != Race.PC_ALL
				&& template.getRacePermitted() != bot.getRace()) {
				log.info("[questsyncdbg] quest={} bot={} skipped: race {} != {}", id, bot.getName(), bot.getRace(),
					template.getRacePermitted());
				continue;
			}
			if (template.getMinlevelPermitted() != 99 && bot.getLevel() < template.getMinlevelPermitted()) {
				log.info("[questsyncdbg] quest={} bot={} skipped: level {} < min {}", id, bot.getName(), bot.getLevel(),
					template.getMinlevelPermitted());
				continue;
			}
			if (template.getMaxlevelPermitted() != 0 && bot.getLevel() > template.getMaxlevelPermitted()) {
				log.info("[questsyncdbg] quest={} bot={} skipped: level {} > max {}", id, bot.getName(), bot.getLevel(),
					template.getMaxlevelPermitted());
				continue;
			}
			if (template.getGenderPermitted() != null && template.getGenderPermitted() != bot.getGender()) {
				log.info("[questsyncdbg] quest={} bot={} skipped: gender {} != {}", id, bot.getName(), bot.getGender(),
					template.getGenderPermitted());
				continue;
			}

			try {
				QuestStateList botQsl = bot.getQuestStateList();
				QuestState botQs = botQsl.getQuestState(id);
				if (botQs == null) {
					botQs = new QuestState(id, QuestStatus.REWARD, 0, 0, 0, null, null, null);
					botQsl.addQuest(id, botQs);
				}
				else {
					botQs.setStatus(QuestStatus.REWARD);
				}

				// A reward the HOST picked from a list (a class-appropriate weapon choice, etc.) can't be
				// synced sight-unseen - the host's chosen dialogId/extendedRewardIndex only means anything
				// in the context of the host's OWN class and the option THEY looked at, and there's no
				// dialog for the bot to answer this with itself (no client). Leaving the bot at REWARD
				// status until they're played directly was tried first, but turned out to be a bigger
				// practical annoyance than it solved: "logging out and in to get the rewards is a touch
				// bit PITA... let's just give each bot all the rewards, then the host can sort it out
				// later." So for a selectable-reward quest, giveRewardAndFinish() below is told to add
				// EVERY item from whichever list applies (the bot's own class's list, if the quest uses
				// isUseClassReward(), else the plain shared list) instead of picking the one index the
				// host happened to choose - inventory clutter is a smaller problem than a wrong-class item
				// or a stuck quest.
				boolean giveAllSelectable = hasSelectableReward(template);
				int botReward = resolveRewardIndexForBot(id, reward, bot);

				QuestEnv botEnv = new QuestEnv(hostEnv.getVisibleObject(), bot, id, hostEnv.getDialogId());
				botEnv.setExtendedRewardIndex(hostEnv.getExtendedRewardIndex());

				boolean botSuccess;
				if (!template.getExtendedRewards().isEmpty() && botQs.getCompleteCount() == template.getMaxRepeatCount() - 1) {
					botSuccess = giveRewardAndFinish(botEnv, template, true, 0, giveAllSelectable);
				}
				else if (!template.getRewards().isEmpty() || !template.getBonus().isEmpty()) {
					botSuccess = giveRewardAndFinish(botEnv, template, false, botReward, giveAllSelectable);
				}
				else {
					botSuccess = setFinishingState(botEnv, template, botReward);
				}
				log.info("[questsyncdbg] quest={} bot={} grant success={} finalStatus={}", id, bot.getName(), botSuccess,
					botQs.getStatus());
			}
			catch (Exception e) {
				// A quest handler side effect (HTML dialog, follow-up spawn, etc.) can assume a real
				// client connection a bot doesn't have - never let that take down the host's own
				// completion (already succeeded before this method was even called) or block syncing to
				// the REST of the group's bots. Confirmed live: HTMLService.sendData() NPEs on
				// player.getClientConnection() for a bot - caught internally there, but this catch is the
				// safety net for anything that ISN'T as careful.
				log.error("[questsyncdbg] quest=" + id + " bot=" + bot.getName() + " sync threw", e);
			}
		}
	}

	/**
	 * True if this quest's reward isn't a single fixed grant - either a per-class list
	 * (isUseClassReward(), e.g. "Ascension"-adjacent class-appropriate weapon quests) or a plain
	 * pick-one-of-several list (Rewards.getSelectableRewardItem()) on either its normal or extended
	 * reward tier. Either way the actual item only gets decided by a dialogId/extendedRewardIndex a
	 * real client sends - see syncQuestCompletionToBots()'s REWARD-status deferral just above.
	 */
	private static boolean hasSelectableReward(QuestTemplate template) {
		if (template.isUseClassReward())
			return true;
		for (Rewards r : template.getRewards())
			if (!r.getSelectableRewardItem().isEmpty())
				return true;
		for (Rewards r : template.getExtendedRewards())
			if (!r.getSelectableRewardItem().isEmpty())
				return true;
		return false;
	}

	private static boolean giveRewardAndFinish(QuestEnv env, QuestTemplate template, boolean extended, int reward,
		boolean giveAllSelectable) {
		Player player = env.getPlayer();
		int id = env.getQuestId();
		List<QuestItems> questItems = new ArrayList<QuestItems>();
		Rewards rewards;
		if (extended) {
			rewards = template.getExtendedRewards().get(reward);
		}
		else {
			rewards = template.getRewards().get(reward);
		}
		questItems.addAll(rewards.getRewardItem());
		int dialogId = env.getDialogId();
		if (giveAllSelectable) {
			// Bot-sync path: no dialog exists to pick just one, so grant the whole relevant list instead
			// of the single index env.getDialogId()/getExtendedRewardIndex() would otherwise select -
			// see syncQuestCompletionToBots() for why. isUseClassReward() quests get the bot's OWN class's
			// list (never another class's), everything else gets the plain shared list as-is.
			if (template.isUseClassReward()) {
				List<QuestItems> classSelectableReward = getClassSelectableReward(template, player.getCommonData().getPlayerClass());
				if (classSelectableReward != null)
					questItems.addAll(classSelectableReward);
			}
			else {
				questItems.addAll(rewards.getSelectableRewardItem());
			}
		}
		else if (dialogId != 18 && dialogId != 0 && !extended) {
			if (template.isUseClassReward()) {
				QuestItems classRewardItem = null;
				PlayerClass playerClass = player.getCommonData().getPlayerClass();
				int selRewIndex = dialogId - 8;
				switch (playerClass) {
					case ASSASSIN: {
						classRewardItem = getQuestItemsbyClass(id, template.getAssassinSelectableReward(), selRewIndex);
						break;
					}
					case CHANTER: {
						classRewardItem = getQuestItemsbyClass(id, template.getChanterSelectableReward(), selRewIndex);
						break;
					}
					case CLERIC: {
						classRewardItem = getQuestItemsbyClass(id, template.getPriestSelectableReward(), selRewIndex);
						break;
					}
					case GLADIATOR: {
						classRewardItem = getQuestItemsbyClass(id, template.getFighterSelectableReward(), selRewIndex);
						break;
					}
					case RANGER: {
						classRewardItem = getQuestItemsbyClass(id, template.getRangerSelectableReward(), selRewIndex);
						break;
					}
					case SORCERER: {
						classRewardItem = getQuestItemsbyClass(id, template.getWizardSelectableReward(), selRewIndex);
						break;
					}
					case SPIRIT_MASTER: {
						classRewardItem = getQuestItemsbyClass(id, template.getElementalistSelectableReward(), selRewIndex);
						break;
					}
					case TEMPLAR: {
						classRewardItem = getQuestItemsbyClass(id, template.getKnightSelectableReward(), selRewIndex);
						break;
					}
				}
				if (classRewardItem != null) {
					questItems.add(classRewardItem);
				}
			}
			else {
				QuestItems selectebleRewardItem = null;
				if (dialogId - 8 >= 0 && dialogId - 8 < rewards.getSelectableRewardItem().size()) {
					selectebleRewardItem = rewards.getSelectableRewardItem().get(dialogId - 8);
				}
				else {
					log.error("The SelectableRewardItem list has no element with the given index (dialogId - 8) of "
						+ (dialogId - 8) + ". See quest id " + env.getQuestId());
				}
				if (selectebleRewardItem != null) {
					questItems.add(selectebleRewardItem);
				}
			}
		}
		else if (dialogId == 18 && extended && !rewards.getSelectableRewardItem().isEmpty()) {
			QuestItems selectebleRewardItem = null;
			int index = env.getExtendedRewardIndex();
			if (index - 8 >= 0 && index - 8 < rewards.getSelectableRewardItem().size()) {
				selectebleRewardItem = rewards.getSelectableRewardItem().get(index - 8);
			}
			else if ((index - 1) >= 0 && (index - 1) < rewards.getSelectableRewardItem().size()) {
				selectebleRewardItem = rewards.getSelectableRewardItem().get(index - 1);
			}
			else {
				log
					.error("The extended SelectableRewardItem list has no element with the given index (extendedRewardIndex - 8) of "
						+ (index - 8) + ". See quest id " + env.getQuestId() + ". The size is: " + rewards.getSelectableRewardItem().size());
			}
			if (selectebleRewardItem != null) {
				questItems.add(selectebleRewardItem);
			}
		}

		if (!template.getBonus().isEmpty()) {
			QuestBonuses bonus = template.getBonus().get(0);
			// Handler can add additional bonuses on repeat (for event quests no data)
			HandlerResult result = QuestEngine.getInstance().onBonusApplyEvent(env, bonus.getType(), questItems);
			if (result == HandlerResult.FAILED)
				return false;
			QuestItems additional = BonusService.getInstance().getQuestBonus(player, template);
			if (additional != null)
				questItems.add(additional);
		}

		// Reward items always get added, even over the normal inventory cap, rather than blocking
		// completion on free slots - requested live: "if it was a reward from a quest it just added it
		// to the inventory and you just had more stuff in there than slots and you had to get rid of a
		// bunch in order for it to work... I suspect this [blocking] is from a really early version [of
		// this server fork]." This applies to every player, not just bots (see ItemService.
		// addQuestItems()'s ignoreInventorySpace parameter) - bots specifically needed this regardless
		// since a companion's cube isn't something the host actively manages, but the block itself
		// wasn't standard/desired behavior for anyone.
		if (ItemService.addQuestItems(player, questItems, true)) {
			if (rewards.getGold() != null) {
				player.getInventory().increaseKinah((long) (player.getRates().getQuestKinahRate() * rewards.getGold()),
					ItemUpdateType.INC_KINAH_QUEST);
			}
			if (rewards.getExp() != null) {
				NpcTemplate npcTemplate = DataManager.NPC_DATA.getNpcTemplate(env.getTargetId());
				player.getCommonData().addExp(rewards.getExp(), RewardType.QUEST,
					npcTemplate != null ? npcTemplate.getNameId() : 0);
			}
			if (rewards.getTitle() != null) {
				player.getTitleList().addTitle(rewards.getTitle(), true, 0);
			}
			if (rewards.getRewardAbyssPoint() != null) {
				AbyssPointsService.addAp(player, (int) (player.getRates().getQuestApRate() * rewards.getRewardAbyssPoint()));
			}
			if (rewards.getExtendInventory() != null) {
				if (rewards.getExtendInventory() == 1) {
					CubeExpandService.expand(player, false);
				}
				else if (rewards.getExtendInventory() == 2) {
					WarehouseService.expand(player);
				}
			}
			if (rewards.getExtendStigma() != null) {
				//StigmaService.extendAdvancedStigmaSlots(player);
			}
			return setFinishingState(env, template, reward);
		}
		else {
			return false;
		}
	}

	private static boolean setFinishingState(QuestEnv env, QuestTemplate template, int reward) {
		Player player = env.getPlayer();
		int id = env.getQuestId();
		QuestState qs = player.getQuestStateList().getQuestState(id);
		// remove all worker list item if finished.
		QuestWorkItems qwi = questsData.getQuestById(id).getQuestWorkItems();
		if (qwi != null) {
			long count = 0;
			for (QuestItems qi : qwi.getQuestWorkItem()) {
				if (qi != null) {
					count = player.getInventory().getItemCountByItemId(qi.getItemId());
					if (count > 0) {
						if (!player.getInventory().decreaseByItemId(qi.getItemId(), count)) {
							return false;
						}
					}
				}
			}
		}
		qs.setStatus(QuestStatus.COMPLETE);
		qs.setQuestVar(0);
		qs.setReward(reward);
		qs.setCompleteCount(qs.getCompleteCount() + 1);
		if (template.getRepeatCycle() != null) { // daily/weekly
			qs.setNextRepeatTime(countNextRepeatTime(player, template));
		}
		PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(id, qs.getStatus(), qs.getQuestVars().getQuestVars()));
		player.getController().updateNearbyQuests();
		QuestEngine.getInstance().onLvlUp(env);
		if (template.getNpcFactionId() != 0) {
			player.getNpcFactions().completeQuest(template);
		}
		return true;
	}

	private static QuestItems getQuestItemsbyClass(int id, List<QuestItems> classSelRew, int selRewIndex) {
		if (selRewIndex >= 0 && selRewIndex < classSelRew.size()) {
			return classSelRew.get(selRewIndex);
		}
		else {
			log.error("Wrong selectable reward index " + selRewIndex + " for quest " + id);
		}
		return null;
	}

	/** The per-class selectable-reward list for whichever class is passed in - mirrors the same
	 * class->accessor mapping giveRewardAndFinish()'s dialogId-indexed branch uses above, just without
	 * picking a single index out of it. */
	private static List<QuestItems> getClassSelectableReward(QuestTemplate template, PlayerClass playerClass) {
		switch (playerClass) {
			case ASSASSIN:
				return template.getAssassinSelectableReward();
			case CHANTER:
				return template.getChanterSelectableReward();
			case CLERIC:
				return template.getPriestSelectableReward();
			case GLADIATOR:
				return template.getFighterSelectableReward();
			case RANGER:
				return template.getRangerSelectableReward();
			case SORCERER:
				return template.getWizardSelectableReward();
			case SPIRIT_MASTER:
				return template.getElementalistSelectableReward();
			case TEMPLAR:
				return template.getKnightSelectableReward();
			default:
				return null;
		}
	}

	/**
	 * Quests 2009 ("A Ceremony in Pandaemonium", Asmodian) and 1007 ("A Ceremony in Sanctum", Elyos) -
	 * the immediate post-ascension follow-up quest where you pick a class-appropriate weapon/accessory -
	 * don't use isUseClassReward() at all. Instead they lay the reward out as four separate <rewards>
	 * blocks in quest_data.xml, one per starting archetype (0=Warrior, 1=Scout, 2=Mage, 3=Priest), and
	 * their handler script picks which index to grant by hardcoding a different literal at each of the
	 * four archetype-specific NPCs - baking in an implicit assumption that whoever completes the quest
	 * IS that archetype. syncQuestCompletionToBots() forwards that same hardcoded index to every bot
	 * regardless of the bot's own class, so a run completed by e.g. a Mage-archetype host was handing
	 * literally every companion bot the Mage-archetype reward block. Confirmed live: "It looks like
	 * everyone got the spiritmasters reward as that was the character I completed it on." This
	 * recomputes the correct block per bot from PlayerClass.getStartingClassFor(), matching the exact
	 * WARRIOR/SCOUT/MAGE/PRIEST -> 0/1/2/3 mapping both quests' own handlers use. Scoped to just these
	 * two ids rather than any quest with multiple <rewards> blocks - other quests use multiple blocks
	 * for unrelated reasons (e.g. per-repeat-count tiers), where reinterpreting the index as an
	 * archetype pick would be wrong.
	 */
	private static int resolveRewardIndexForBot(int questId, int hostReward, BotPlayer bot) {
		if (questId != 2009 && questId != 1007)
			return hostReward;
		switch (PlayerClass.getStartingClassFor(bot.getCommonData().getPlayerClass())) {
			case WARRIOR:
				return 0;
			case SCOUT:
				return 1;
			case MAGE:
				return 2;
			case PRIEST:
				return 3;
			default:
				return hostReward;
		}
	}

	private static Timestamp countNextRepeatTime(Player player, QuestTemplate template) {
		DateTime now = DateTime.now();
		DateTime repeatDate;
		if(now.getHourOfDay() < 9)	
			 repeatDate = new DateTime(now.getYear(), now.getMonthOfYear(), now.getDayOfMonth(), 9, 0, 0).minusDays(1);
		else
			 repeatDate = new DateTime(now.getYear(), now.getMonthOfYear(), now.getDayOfMonth(), 9, 0, 0);
		if (template.getRepeatCycle() == QuestRepeatCycle.ALL) {
			if (now.isAfter(repeatDate))
				repeatDate = repeatDate.plusHours(24);
			PacketSendUtility.sendPacket(player, new SM_SYSTEM_MESSAGE(1400855, "9"));
		}
		else {
			int daysToAdd = repeatDate.getDayOfWeek() - template.getRepeatCycle().ordinal();
            if (daysToAdd <  0)
                daysToAdd = Math.abs(daysToAdd);
            else
                daysToAdd = 7 - daysToAdd;
			repeatDate = repeatDate.plusDays(daysToAdd);
			PacketSendUtility.sendPacket(player, new SM_SYSTEM_MESSAGE(1400857, new DescriptionId(1800663), "9"));
		}
		return new Timestamp(repeatDate.getMillis());
	}

	/**
	 * This method will not propagate any exceptions to the caller
	 * 
	 * @param env
	 * @return
	 */
	public static boolean checkStartConditions(QuestEnv env) {
		try {
			return checkStartConditionsImpl(env);
		}
		catch (Exception ex) {
			log.error("QE: exception in checkStartCondition", ex);
		}
		return false;
	}

	private static boolean checkStartConditionsImpl(QuestEnv env) {
		Player player = env.getPlayer();
		QuestTemplate template = questsData.getQuestById(env.getQuestId());

		if (template == null)
			return false;

		if (template.getRacePermitted() != null)
			if (template.getRacePermitted() != player.getRace() && template.getRacePermitted() != Race.PC_ALL)
				return false;

		// min level - 2 so that the gray quest arrow shows when quest is almost available
		// quest level will be checked again in QuestService.startQuest() when attempting to start
		if ((player.getLevel() < template.getMinlevelPermitted() - 2) && (template.getMinlevelPermitted() != 99))
			return false;

		if (template.getMaxlevelPermitted() != 0 && player.getLevel() > template.getMaxlevelPermitted())
			return false;

		if (template.getClassPermitted().size() != 0)
			if (!template.getClassPermitted().contains(player.getCommonData().getPlayerClass()))
				return false;

		if (template.getGenderPermitted() != null)
			if (template.getGenderPermitted() != player.getGender())
				return false;

		int amountOfStartConditions = template.getXMLStartConditions().size();
		int fulfilledStartConditions = 0;
		if (amountOfStartConditions != 0) {
			for (XMLStartCondition startCondition : template.getXMLStartConditions()) {
				if (startCondition.check(player)) {
					fulfilledStartConditions++;
				}
			}
			if (fulfilledStartConditions < 1) {
				return false;
			}
		}

		if (template.getCombineSkill() != null) {
			List<Integer> skills = new ArrayList<Integer>(); // skills to check
			if (template.getCombineSkill() == -1) // any skill
			{
				skills.add(30002);
				skills.add(30003);
				skills.add(40001);
				skills.add(40002);
				skills.add(40003);
				skills.add(40004);
				skills.add(40007);
				skills.add(40008);
			}
			else {
				skills.add(template.getCombineSkill());
			}
			boolean result = false;
			for (int skillId : skills) {
				PlayerSkillEntry skill = player.getSkillList().getSkillEntry(skillId);
				if (skill != null && skill.getSkillLevel() >= template.getCombineSkillPoint()
					&& skill.getSkillLevel() - 40 <= template.getCombineSkillPoint()) {
					result = true;
					break;
				}
			}
			if (!result)
				return false;
		}

		// Check for updating nearby quests
		QuestState qs = player.getQuestStateList().getQuestState(template.getId());
		if (qs != null && qs.getStatus() != QuestStatus.NONE) {
			if (!qs.canRepeat()) {
				return false;
			}
		}
		return true;
	}

	/*
	 * Check the starting conditions and start a quest Reworked 12.06.2011
	 * @author vlog
	 */
	public static boolean startQuest(QuestEnv env, QuestStatus status) {
		Player player = env.getPlayer();
		int id = env.getQuestId();
		QuestStateList qsl = player.getQuestStateList();
		QuestState qs = qsl.getQuestState(id);
		QuestTemplate template = questsData.getQuestById(env.getQuestId());
		if (template.getNpcFactionId() != 0) {
			NpcFaction faction = player.getNpcFactions().getNpcFactinById(template.getNpcFactionId());
			if (!faction.isActive() || faction.getQuestId() != env.getQuestId()) {
				AuditLogger.info(player, "Possible packet hack learn Guild quest");
				return false;
			}
		}
		if (!checkStartConditions(env)) {
			return false;
		}
		if ((player.getLevel() < template.getMinlevelPermitted()) && (template.getMinlevelPermitted() != 99)) {
			return false;
		}

		if (template.getCategory() != QuestCategory.EVENT && !checkQuestListSize(qsl)
			&& !player.havePermission(MembershipConfig.QUEST_LIMIT_DISABLED)) {
			PacketSendUtility.sendPacket(player, new SM_SYSTEM_MESSAGE(1300622, template.getName()));
			return false;
		}

		if (qs != null) {
			if (!qs.canRepeat()) {
				return false;
			}
			qs.setStatus(status);
		}
		else {
			player.getQuestStateList().addQuest(id, new QuestState(id, status, 0, 0, 0, null, 0, null));
		}

		if (template.getNpcFactionId() != 0 && !template.isTimeBased()) {
			player.getNpcFactions().startQuest(template);
		}

		PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(id, status.value(), 0));
		player.getController().updateNearbyQuests();
		return true;
	}

	/*
	 * Check the starting conditions and start a quest Reworked 12.06.2011
	 * @author vlog
	 */
	public static boolean startQuest(QuestEnv env) {
		return startQuest(env, QuestStatus.START);
	}

	/**
	 * Starts or temporary locks the mission Used only from the QuestHandler class
	 * 
	 * @param env
	 * @param status
	 *          START or LOCKED
	 */
	public static void startMission(QuestEnv env, QuestStatus status) {
		Player player = env.getPlayer();
		int questId = env.getQuestId();

		if (player.getQuestStateList().getQuestState(questId) != null)
			return;
		else
			player.getQuestStateList().addQuest(questId, new QuestState(questId, status, 0, 0, 0, null, 0, null));

		PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(questId, status.value(), 0));
	}

	/**
	 * Check the mission start requirements
	 * 
	 * @param env
	 * @return true, if all requirements are there
	 */
	public static boolean checkMissionStatConditions(QuestEnv env) {
		Player player = env.getPlayer();
		QuestTemplate template = questsData.getQuestById(env.getQuestId());

		// Check template existence
		if (template == null)
			return false;

		// Check permitted race
		if (template.getRacePermitted() != null && template.getRacePermitted() != player.getRace())
			return false;

		// Check permitted class
		if (template.getClassPermitted().size() != 0
			&& !template.getClassPermitted().contains(player.getCommonData().getPlayerClass()))
			return false;

		// Check permitted gender
		if (template.getGenderPermitted() != null && template.getGenderPermitted() != player.getGender())
			return false;

		// Check required skills
		if (template.getCombineSkill() != null) {
			List<Integer> skills = new ArrayList<Integer>(); // skills to check
			if (template.getCombineSkill() == -1) // any skill
			{
				skills.add(30002);
				skills.add(30003);
				skills.add(40001);
				skills.add(40002);
				skills.add(40003);
				skills.add(40004);
				skills.add(40007);
				skills.add(40008);
			}
			else {
				skills.add(template.getCombineSkill());
			}
			boolean result = false;
			for (int skillId : skills) {
				PlayerSkillEntry skill = player.getSkillList().getSkillEntry(skillId);
				if (skill != null && skill.getSkillLevel() >= template.getCombineSkillPoint()
					&& skill.getSkillLevel() - 40 <= template.getCombineSkillPoint()) {
					result = true;
					break;
				}
			}
			if (!result)
				return false;
		}

		// Everything is ok
		return true;
	}

	public static boolean startEventQuest(QuestEnv env, QuestStatus questStatus) {
		QuestTemplate template = questsData.getQuestById(env.getQuestId());
		if (template.getCategory() != QuestCategory.EVENT)
			return false;

		int id = env.getQuestId();
		Player player = env.getPlayer();

		PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(id, questStatus, 0));
		QuestState qs = player.getQuestStateList().getQuestState(id);
		if (qs == null) {
			qs = new QuestState(template.getId(), questStatus, 0, 0, 0, null, 0, null);
			player.getQuestStateList().addQuest(id, qs);
		}
		else {
			if (template.getMaxRepeatCount() >= qs.getCompleteCount()) {
				qs.setStatus(questStatus);
				qs.setQuestVar(0);
			}
		}

		player.getController().updateNearbyQuests();
		return true;
	}

	/*
	 * Check the player's quest list size for starting a new one Issue #13 fix
	 * @param quest state list
	 */
	private static boolean checkQuestListSize(QuestStateList qsl) {
		// The player's quest list size + the new one to start
		return (qsl.getNormalQuestListSize() + 1) <= CustomConfig.BASIC_QUEST_SIZE_LIMIT;
	}

	public boolean completeQuest(QuestEnv env) {
		Player player = env.getPlayer();
		int id = env.getQuestId();
		QuestState qs = player.getQuestStateList().getQuestState(id);
		if (qs == null || qs.getStatus() != QuestStatus.START)
			return false;

		qs.setQuestVarById(0, qs.getQuestVarById(0) + 1);
		qs.setStatus(QuestStatus.REWARD);
		PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(id, qs.getStatus(), qs.getQuestVars().getQuestVars()));
		player.getController().updateNearbyQuests();
		return true;
	}

	public static boolean collectItemCheck(QuestEnv env, boolean removeItem) {
		Player player = env.getPlayer();
		QuestState qs = player.getQuestStateList().getQuestState(env.getQuestId());
		if (qs == null)
			return false;
		QuestTemplate template = questsData.getQuestById(env.getQuestId());
		CollectItems collectItems = template.getCollectItems();
		if (collectItems == null)
			return true;

		for (CollectItem collectItem : collectItems.getCollectItem()) {
			int itemId = collectItem.getItemId();
			long count = itemId == ItemId.KINAH.value() ? player.getInventory().getKinah() : player.getInventory()
				.getItemCountByItemId(itemId);
			if (collectItem.getCount() > count)
				return false;
		}
		if (removeItem) {
			for (CollectItem collectItem : collectItems.getCollectItem()) {
				if (collectItem.getItemId() == 182400001)
					player.getInventory().decreaseKinah(collectItem.getCount());
				else {
					player.getInventory().decreaseByItemId(collectItem.getItemId(), collectItem.getCount());
				}
			}
		}
		return true;
	}

	public static VisibleObject addNewSpawn(int worldId, int instanceId, int templateId, float x, float y, float z,
		byte heading) {
		return SpawnEngine
			.spawnObject(SpawnEngine.addNewSingleTimeSpawn(worldId, templateId, x, y, z, heading), instanceId);
	}

	public static int getQuestDrop(Set<DropItem> dropItems, int index, Npc npc, Collection<Player> players, Player player) {
		Collection<QuestDrop> drops = getQuestDrop(npc.getNpcId());
		if (drops.isEmpty()) {
			return index;
		}
		DropNpc dropNpc = DropRegistrationService.getInstance().getDropRegistrationMap().get(npc.getObjectId());
		for (QuestDrop drop : drops) {
			if (Rnd.get() * 100 > drop.getChance()) {
				continue;
			}
			if (players != null && player.isInGroup2()) {
				List<Player> pls = new ArrayList<Player>();
				if (drop.isDropEachMember()) {
					for (Player member : players) {
						if (isQuestDrop(member, drop)) {
							pls.add(member);
							dropItems.add(regQuestDropItem(drop, index++, member.getObjectId()));
						}
					}
				}
				else {
					for (Player member : players) {
						if (isQuestDrop(member, drop)) {
							pls.add(member);
							break;
						}
					}
				}
				if (pls.size() > 0) {
					if (!drop.isDropEachMember()) {
						dropItems.add(regQuestDropItem(drop, index++, 0));
					}
					for (Player p : pls) {
						dropNpc.setPlayerObjectId(p.getObjectId());
						if (player.getPlayerGroup2().getLootGroupRules().getLootRule() != LootRuleType.FREEFORALL) {
							PacketSendUtility.sendPacket(p, new SM_LOOT_STATUS(npc.getObjectId(), 0));
						}
					}
					pls.clear();
				}
			}
			else {
				if (isQuestDrop(player, drop)) {
					dropItems.add(regQuestDropItem(drop, index++, player.getObjectId()));
				}
			}
		}
		return index;
	}

	private static DropItem regQuestDropItem(QuestDrop drop, int index, Integer winner) {
		DropItem item = new DropItem(new Drop(drop.getItemId(), 1, 1, drop.getChance(), false));
		item.setPlayerObjId(winner);
		item.setIndex(index);
		item.setCount(1);
		return item;
	}

	private static boolean isQuestDrop(Player player, QuestDrop drop) {
		int questId = drop.getQuestId();
		QuestState qs = player.getQuestStateList().getQuestState(questId);
		if (qs == null || qs.getStatus() != QuestStatus.START)
			return false;
		QuestTemplate qt = DataManager.QUEST_DATA.getQuestById(questId);
		if (qt.getMentorType() == QuestMentorType.MENTE) {
			if (!player.isInGroup2())
				return false;

			PlayerGroup group = player.getPlayerGroup2();
			boolean found = false;
			for (Player member : group.getMembers()) {
				if (member.isMentor() && MathUtil.getDistance(player, member) < GroupConfig.GROUP_MAX_DISTANCE) {
					found = true;
					break;
				}
			}
			if (!found)
				return false;
		}
		if (drop instanceof HandlerSideDrop) {
			if (((HandlerSideDrop) drop).getNeededAmount() <= player.getInventory().getItemCountByItemId(drop.getItemId())) {
				return false;
			}
			else {
				return true;
			}
		}

		CollectItems collectItems = questsData.getQuestById(questId).getCollectItems();
		if (collectItems == null)
			return true;

		for (CollectItem collectItem : collectItems.getCollectItem()) {
			int collectItemId = collectItem.getItemId();
			if (collectItemId != drop.getItemId())
				continue;
			long count = player.getInventory().getItemCountByItemId(collectItemId);
			if (collectItem.getCount() > count)
				return true;
		}
		return false;
	}

	/**
	 * @param id
	 * @param playerLevel
	 * @return false if player is 2 or more levels below quest level
	 */
	public static boolean checkLevelRequirement(int questId, int playerLevel) {
		return playerLevel >= questsData.getQuestById(questId).getMinlevelPermitted();
	}

	public static boolean questTimerStart(QuestEnv env, int timeInSeconds) {
		final Player player = env.getPlayer();

		// Schedule Action When Timer Finishes
		Future<?> task = ThreadPoolManager.getInstance().schedule(new Runnable() {

			@Override
			public void run() {
				QuestEngine.getInstance().onQuestTimerEnd(new QuestEnv(null, player, 0, 0));
			}
		}, timeInSeconds * 1000);
		player.getController().addTask(TaskId.QUEST_TIMER, task);
		PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(env.getQuestId(), timeInSeconds));
		return true;
	}

	public static boolean questTimerEnd(QuestEnv env) {
		final Player player = env.getPlayer();

		player.getController().cancelTask(TaskId.QUEST_TIMER);
		PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(env.getQuestId(), 0));
		return true;
	}

	public static boolean abandonQuest(Player player, int questId) {
		QuestTemplate template = questsData.getQuestById(questId);
		if (template == null) {
			return false;
		}
		if (template.isCannotGiveup())
			return false;

		QuestState qs = player.getQuestStateList().getQuestState(questId);

		if (qs == null)
			return false;

		if (qs.getStatus() == QuestStatus.COMPLETE || qs.getStatus() == QuestStatus.LOCKED) {
			AuditLogger.info(player, "Cancel from completed quest. quest Id: " + questId);
			return false;
		}
		if (template.getNpcFactionId() != 0)
			player.getNpcFactions().abortQuest(template);
		qs.setStatus(QuestStatus.NONE);
		qs.setQuestVar(0);
		// remove all worker list item if abandoned
		QuestWorkItems qwi = template.getQuestWorkItems();
		if (qwi != null) {
			long count = 0;
			for (QuestItems qi : qwi.getQuestWorkItem()) {
				if (qi != null) {
					count = player.getInventory().getItemCountByItemId(qi.getItemId());
					if (count > 0)
						player.getInventory().decreaseByItemId(qi.getItemId(), count);
				}
			}
		}
		if (template.getCategory() == QuestCategory.TASK) {
			WorkOrdersData wod = null;
			for (XMLQuest xmlQuest : DataManager.XML_QUESTS.getQuest()) {
				if (xmlQuest.getId() == questId) {
					if (xmlQuest instanceof WorkOrdersData) {
						wod = (WorkOrdersData) xmlQuest;
						break;
					}
				}
			}
			if (wod != null) {
				player.getRecipeList().deleteRecipe(player, wod.getRecipeId());
			}
		}

		if (player.getController().getTask(TaskId.QUEST_TIMER) != null)
			questTimerEnd(new QuestEnv(null, player, questId, 0));

		PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(questId));
		player.getController().updateNearbyQuests();
		return true;
	}

	public static Collection<QuestDrop> getQuestDrop(int npcId) {
		if (questDrop.containsKey(npcId)) {
			return questDrop.get(npcId);
		}
		return Collections.<QuestDrop> emptyList();
	}

	public static void addQuestDrop(int npcId, QuestDrop drop) {
		if (!questDrop.containsKey(npcId)) {
			questDrop.put(npcId, drop);
		}
		else {
			questDrop.get(npcId).add(drop);
		}
	}

	public static List<Player> getEachDropMembers(PlayerGroup group, int npcId, int questId) {
		List<Player> players = new ArrayList<Player>();
		for (QuestDrop qd : getQuestDrop(npcId)) {
			if (qd.isDropEachMember()) {
				for (Player player : group.getMembers()) {
					QuestState qstel = player.getQuestStateList().getQuestState(questId);
					if (qstel != null && qstel.getStatus() == QuestStatus.START) {
						players.add(player);
					}
				}
				break;
			}
		}
		return players;
	}
}
