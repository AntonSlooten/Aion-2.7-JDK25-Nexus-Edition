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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import gnu.trove.list.array.TIntArrayList;

import com.aionemu.gameserver.configs.main.CompanionConfig;
import com.aionemu.gameserver.controllers.movement.BotSummonMoveController;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Summon;
import com.aionemu.gameserver.model.gameobjects.player.BotPlayer;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.skill.PlayerSkillEntry;
import com.aionemu.gameserver.model.team2.group.PlayerGroup;
import com.aionemu.gameserver.skillengine.action.Action;
import com.aionemu.gameserver.skillengine.action.Actions;
import com.aionemu.gameserver.skillengine.action.HpUseAction;
import com.aionemu.gameserver.skillengine.model.SkillSubType;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.FirstTargetAttribute;
import com.aionemu.gameserver.skillengine.properties.Properties;
import com.aionemu.gameserver.skillengine.properties.TargetRangeAttribute;
import com.aionemu.gameserver.skillengine.properties.TargetRelationAttribute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a Spirit Master bot's pact-spirit summon: which element to summon (party-comp aware) and, once
 * it's out, which of the pet's own order-skills to use each tick. Deliberately its own class rather than
 * folded into {@link PlayerBotSkillSelector} - unlike everything there, this genuinely IS class-specific
 * (only a Spirit Master ever owns a SkillSubType.SUMMON skill matching one of the four pact-spirit stack
 * markers below), and the pet's skills are commanded through a completely different path
 * ({@link com.aionemu.gameserver.controllers.SummonController#useSkill}, not the bot's own
 * useSkill()/PlayerSkillEntry machinery).
 */
public class PlayerBotSummonSelector {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotSummonSelector.class);

	private PlayerBotSummonSelector() {
	}

	/**
	 * Element stack markers (see each Summon-effect skill's stack="SKILL_EL_LIGHT_SUMMON_..."/
	 * "SKILL_EL_DARK_SUMMON_..." - both factions share the same *_SUMMON_<ELEMENT>ELEMENTAL suffix, so
	 * matching the suffix alone is faction-agnostic). Chosen per live stat data: Earth has ~2.5x Water's
	 * HP and by far the best defense of the four (a real tank), Fire sits in the middle with solid attack
	 * and moderate durability. Requested live: "if there is no templar, summon a tree - or if there is
	 * one, summon a DPS class."
	 */
	private static final String ELEMENT_EARTH = "SUMMON_EARTHELEMENTAL";
	private static final String ELEMENT_FIRE = "SUMMON_FIREELEMENTAL";
	private static final String ELEMENT_WATER = "SUMMON_WATERELEMENTAL";
	private static final String ELEMENT_WIND = "SUMMON_WINDELEMENTAL";
	private static final String[] ANY_ELEMENT = { ELEMENT_EARTH, ELEMENT_FIRE, ELEMENT_WATER, ELEMENT_WIND };

	/**
	 * Entry point, called every think() tick regardless of class - no-ops immediately for any bot that
	 * doesn't own a pact-spirit summon skill at all.
	 */
	public static void manageSummon(BotPlayer bot, Player host, Creature engageTarget) {
		Summon summon = bot.getSummon();
		if (summon == null || summon.getLifeStats().isAlreadyDead()) {
			maybeSummonSpirit(bot, host);
			return;
		}

		TIntArrayList petSkillIds = DataManager.PET_SKILL_DATA.getPetSkills(summon.getObjectTemplate().getTemplateId());
		BotSummonMoveController moveController = (BotSummonMoveController) summon.getMoveController();
		// Out of combat, just follow the bot like the companions already do. In combat, close on the
		// actual enemy instead - staying glued to the bot left the pet unable to reach anything, since
		// most of its order-skills only reach 2-18 units, not wherever the bot itself happens to be
		// standing. Requested live (clarified): "target here means run towards enemies."
		if (engageTarget != null && petSkillIds != null)
			moveController.moveToTargetObject(engageTarget, effectiveSummonRange(summon, petSkillIds));
		else
			moveController.moveToTargetObject(bot, BotSummonMoveController.FOLLOW_OFFSET);

		if (petSkillIds != null)
			driveSummon(bot, summon, engageTarget, petSkillIds);
	}

	/** The farthest the pet can meaningfully act from right now - its own base attack range, or further
	 * if any owned enemy-targeted order-skill reaches beyond that. Mirrors
	 * PlayerBotSkillSelector.effectiveEngageRange()'s shape for the bot itself. */
	private static float effectiveSummonRange(Summon summon, TIntArrayList petSkillIds) {
		float best = summon.getGameStats().getAttackRange().getCurrent() / 1000f;
		for (int i = 0; i < petSkillIds.size(); i++) {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(petSkillIds.get(i));
			if (template == null || !isEnemyTargeted(template) || costsOwnHp(template))
				continue;
			Properties properties = template.getProperties();
			if (properties == null)
				continue;
			int range = properties.getFirstTargetRange();
			if (range > best)
				best = range;
		}
		return best;
	}

	/**
	 * No Templar in the group -> summon the tanky Earth spirit; otherwise the more offensive Fire spirit.
	 * Only acts when the bot has no live summon at all - deliberately does NOT re-summon on a comp change
	 * mid-fight (e.g. a Templar joining later), which would waste a healthy pet for a marginal gain.
	 *
	 * Each pact spirit unlocks at a different character level (Fire 18, Wind 21, Earth 24, Water 27 in
	 * this data), so a lower-level Spirit Master may not have learned the "preferred" element for their
	 * current party comp at all yet - without a fallback, that meant no summon whatsoever until they
	 * happened to hit the right level. Confirmed live: "if I am not a templar, the summoning does not
	 * work" (Earth, the no-Templar pick, unlocks latest of the four). Falls back to whichever element the
	 * bot actually knows, highest tier owned, rather than doing nothing.
	 */
	private static void maybeSummonSpirit(BotPlayer bot, Player host) {
		PlayerSkillEntry[] skills = bot.getSkillList().getAllSkills();
		PlayerSkillEntry summonSkill = findBestSummonSkill(skills, groupHasTemplar(host) ? ELEMENT_FIRE : ELEMENT_EARTH);
		if (summonSkill == null)
			summonSkill = findBestSummonSkill(skills, ANY_ELEMENT);
		if (summonSkill == null)
			return; // not a Spirit Master, or hasn't learned any pact-spirit summon yet
		if (!PlayerBotSkillSelector.isActuallyUsable(bot, summonSkill.getSkillTemplate()))
			return;

		bot.setTarget(bot);
		boolean success = bot.getController().useSkill(summonSkill.getSkillId(), summonSkill.getSkillLevel());
		if (!success)
			bot.markSkillFailed(summonSkill.getSkillId());
		if (CompanionConfig.DEBUG_LOGGING)
			log.info("[bot {}] SUMMON skillId={} -> useSkill={}", bot.getObjectId(), summonSkill.getSkillId(), success);
	}

	private static boolean groupHasTemplar(Player host) {
		PlayerGroup group = host.getPlayerGroup2();
		if (group == null)
			return host.getCommonData().getPlayerClass() == PlayerClass.TEMPLAR;
		for (Player member : group.getMembers())
			if (member.getCommonData().getPlayerClass() == PlayerClass.TEMPLAR)
				return true;
		return false;
	}

	/**
	 * Matches only against the given pact-spirit element marker(s) - never "any SkillSubType.SUMMON skill
	 * regardless of stack", which an earlier version of the ANY_ELEMENT fallback effectively did by
	 * passing null here. That was a real bug: SkillSubType.SUMMON also covers unrelated Abyss Point rank
	 * reward skills (e.g. "Summon Abyssal Energy II", stack="SKILL_ABYSS_RANKERSKILL_D_ABYSSIANSERVANT",
	 * costs 16500 MP, summons an "abyssal servant" with nothing to do with the SM class) that any
	 * high-enough-rank character can have regardless of build - the fallback was picking one of those up
	 * on any non-SM bot that happened to own one, and repeatedly failing to cast it. Confirmed live:
	 * "[bot X] SUMMON skillId=11902 -> useSkill=false" spamming on a Gladiator/Assassin bot that isn't a
	 * Spirit Master at all.
	 */
	private static PlayerSkillEntry findBestSummonSkill(PlayerSkillEntry[] skills, String... elementMarkers) {
		PlayerSkillEntry best = null;
		for (PlayerSkillEntry entry : skills) {
			SkillTemplate template = entry.getSkillTemplate();
			if (template == null || template.getSubType() != SkillSubType.SUMMON)
				continue;
			String stack = template.getStack();
			if (stack == null)
				continue;
			boolean matchesElement = false;
			for (String marker : elementMarkers) {
				if (stack.contains(marker)) {
					matchesElement = true;
					break;
				}
			}
			if (!matchesElement)
				continue;
			best = PlayerBotSkillSelector.higherRank(best, entry);
		}
		return best;
	}

	/**
	 * Self-buff maintenance first (cheap, never contested by a target), then an attack/CC rotation against
	 * the current combat target - same tiered-cascade shape as PlayerBotSkillSelector, minus the heal/
	 * cleanse tiers a pact spirit's kit doesn't have. Any order-skill whose own cast costs the pet's HP
	 * (see costsOwnHp()) is excluded from both tiers entirely, not just de-prioritized - confirmed live in
	 * this data: every element has a "Command: X Transference" (spends 20% of the pet's own HP to grant an
	 * ally 1000 MP) and four "ultimate" AoE nukes (Flow/Impact/Self-Destruct/Burn-to-Ashes/Explosion,
	 * costing 40% of the pet's own HP each). A rotation with no judgment about when that tradeoff is worth
	 * it has no business spending them automatically.
	 */
	private static void driveSummon(BotPlayer bot, Summon summon, Creature engageTarget, TIntArrayList petSkillIds) {
		if (engageTarget == null)
			return;

		// Buff tier only fires once there's actually a fight - these are combat buffs (attack%/damage
		// reflector), not passive upkeep, so popping them the instant the pet spawns rather than when a
		// fight actually starts wastes their duration sitting idle. Requested live: "the summon probably
		// wants to cast it's buff skills in battle, not outside of battle."
		SkillTemplate buff = findMissingSelfBuff(bot, summon, petSkillIds);
		if (buff != null && castPetSkill(bot, summon, buff, summon))
			return;

		for (SkillTemplate offensive : rankedOffensiveSkills(bot, summon, petSkillIds))
			if (castPetSkill(bot, summon, offensive, engageTarget))
				return;
	}

	private static SkillTemplate findMissingSelfBuff(BotPlayer bot, Summon summon, TIntArrayList petSkillIds) {
		SkillTemplate best = null;
		for (int i = 0; i < petSkillIds.size(); i++) {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(petSkillIds.get(i));
			if (template == null || template.getSubType() != SkillSubType.BUFF || !isSelfTargeted(template))
				continue;
			if (costsOwnHp(template))
				continue;
			if (!isPetSkillUsable(bot, summon, template))
				continue;
			if (isBuffActive(summon, template))
				continue;
			best = higherLevel(best, template);
		}
		return best;
	}

	private static List<SkillTemplate> rankedOffensiveSkills(BotPlayer bot, Summon summon, TIntArrayList petSkillIds) {
		List<SkillTemplate> matches = new ArrayList<SkillTemplate>();
		for (int i = 0; i < petSkillIds.size(); i++) {
			SkillTemplate template = DataManager.SKILL_DATA.getSkillTemplate(petSkillIds.get(i));
			if (template == null || !isEnemyTargeted(template))
				continue;
			if (costsOwnHp(template))
				continue;
			if (!isPetSkillUsable(bot, summon, template))
				continue;
			matches.add(template);
		}
		Collections.sort(matches, new Comparator<SkillTemplate>() {
			@Override
			public int compare(SkillTemplate a, SkillTemplate b) {
				return b.getLvl() - a.getLvl();
			}
		});
		return matches;
	}

	/**
	 * True only for a skill that actually LANDS on the caster, not merely one centered/anchored on the
	 * caster's position - first_target="ME" alone covers both. "Spirit Wall of Protection" is first_target=
	 * "ME" but target_type="AREA"/target_maxcount="6": an AoE support buff cast FROM the pet's position
	 * that lands on nearby FRIEND allies, not necessarily the pet itself. Without the ONLYONE check here,
	 * isBuffActive() (which only ever checks the SUMMON's own effect list) never found it "active" on the
	 * pet - who may never actually be in its own AoE's effective target set - so it looked permanently
	 * missing and got recast every single tick. Confirmed live: "the summon casts spirit wall of
	 * protection constantly on the SM". ONLYONE + first_target=ME is the pattern every genuinely
	 * self-landing buff in this kit actually uses (e.g. "Spirit Wrath Position").
	 */
	private static boolean isSelfTargeted(SkillTemplate template) {
		Properties properties = template.getProperties();
		return properties != null && properties.getFirstTarget() == FirstTargetAttribute.ME
			&& properties.getTargetType() == TargetRangeAttribute.ONLYONE;
	}

	private static boolean isEnemyTargeted(SkillTemplate template) {
		Properties properties = template.getProperties();
		return properties != null && properties.getTargetRelation() == TargetRelationAttribute.ENEMY;
	}

	/**
	 * True for any order-skill whose <actions> spend the CASTER's (the pet's) own HP to go off - see this
	 * class's javadoc. Structural detection (an HpUseAction present at all) rather than a hardcoded skillId
	 * list, so it automatically covers every element/tier without needing to be kept in sync with new ones.
	 */
	private static boolean costsOwnHp(SkillTemplate template) {
		Actions actions = template.getActions();
		if (actions == null)
			return false;
		for (Action action : actions.getActions())
			if (action instanceof HpUseAction)
				return true;
		return false;
	}

	private static boolean isBuffActive(Summon summon, SkillTemplate template) {
		String stack = template.getStack();
		if (stack != null && !stack.isEmpty())
			return summon.getEffectController().getAnormalEffect(stack) != null;
		return summon.getEffectController().isAbnormalPresentBySkillId(template.getSkillId());
	}

	private static SkillTemplate higherLevel(SkillTemplate current, SkillTemplate candidate) {
		if (current == null)
			return candidate;
		return candidate.getLvl() > current.getLvl() ? candidate : current;
	}

	/**
	 * Cooldown/failure gate for a pet order-skill, mirroring PlayerBotSkillSelector.isActuallyUsable() but
	 * for a Summon rather than the bot itself - Creature.isSkillDisabled() covers the skill's own cooldown
	 * directly. Reuses the bot's own skill-failure-backoff cache (keyed only by skillId, which is globally
	 * unique across the whole game's skill_templates.xml - a pet order-skill's ID can never collide with
	 * anything on the bot's own kit) rather than adding a second cache just for pet skills.
	 */
	private static boolean isPetSkillUsable(BotPlayer bot, Summon summon, SkillTemplate template) {
		if (summon.isSkillDisabled(template))
			return false;
		return !bot.isSkillOnFailureCooldown(template.getSkillId());
	}

	/**
	 * Every pet order-skill in this data has cooldown="0" - a real player is naturally throttled by their
	 * own reaction time, and CM_SUMMON_CASTSPELL additionally hard-throttles to one cast per 1100ms
	 * (Player.setNextSummonSkillUse()) regardless. Bypassing that packet handler entirely (this class
	 * calls SummonController.useSkill() directly) meant nothing throttled the pet at all, and it fired a
	 * new attack every single think() tick (~750ms, faster than even a real player's throttle) - confirmed
	 * live: "there appears to be no cooldown on attacks, and the summon mows down enemies nearly
	 * instantly". Reuses the bot's own getNextSummonSkillUse()/setNextSummonSkillUse() - the exact same
	 * field and pacing a real client-driven cast goes through.
	 */
	private static final long SUMMON_SKILL_THROTTLE_MS = 1100L;

	private static boolean castPetSkill(BotPlayer bot, Summon summon, SkillTemplate template, Creature target) {
		long now = System.currentTimeMillis();
		if (bot.getNextSummonSkillUse() > now)
			return false;

		boolean success = summon.getController().useSkill(template.getSkillId(), target);
		if (success)
			bot.setNextSummonSkillUse(now + SUMMON_SKILL_THROTTLE_MS);
		else
			bot.markSkillFailed(template.getSkillId());
		if (CompanionConfig.DEBUG_LOGGING)
			log.info("[bot {}] pet skillId={} target={} -> useSkill={}", bot.getObjectId(), template.getSkillId(),
				target == summon ? "self" : target.getObjectId(), success);
		return success;
	}
}
