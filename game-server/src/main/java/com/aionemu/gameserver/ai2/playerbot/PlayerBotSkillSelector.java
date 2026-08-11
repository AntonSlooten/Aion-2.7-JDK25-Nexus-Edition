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

import com.aionemu.gameserver.configs.main.CompanionConfig;
import com.aionemu.gameserver.controllers.attack.AttackStatus;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.BotPlayer;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.skill.PlayerSkillEntry;
import com.aionemu.gameserver.model.team2.group.PlayerGroup;
import com.aionemu.gameserver.skillengine.condition.ChainCondition;
import com.aionemu.gameserver.skillengine.condition.Condition;
import com.aionemu.gameserver.skillengine.condition.Conditions;
import com.aionemu.gameserver.skillengine.model.Effect;
import com.aionemu.gameserver.skillengine.periodicaction.HpUsePeriodicAction;
import com.aionemu.gameserver.skillengine.periodicaction.MpUsePeriodicAction;
import com.aionemu.gameserver.skillengine.periodicaction.PeriodicAction;
import com.aionemu.gameserver.skillengine.periodicaction.PeriodicActions;
import com.aionemu.gameserver.skillengine.model.SkillSubType;
import com.aionemu.gameserver.skillengine.model.SkillTargetSlot;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.FirstTargetAttribute;
import com.aionemu.gameserver.skillengine.properties.Properties;
import com.aionemu.gameserver.skillengine.properties.TargetRangeAttribute;
import com.aionemu.gameserver.skillengine.properties.TargetRelationAttribute;
import com.aionemu.gameserver.utils.MathUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class-agnostic companion bot rotation picker: walks the bot's own real, learned skill list
 * (whatever class it happens to be) and picks the highest-priority off-cooldown action available -
 * group-aware heal, then cleanse, then res, then buffs (party-wide, self, then per-ally), then
 * attack. Never hardcodes a per-class skill table - a Cleric bot heals/cleanses the group because
 * it's the only class with those skills, not because anything here knows it's "a Cleric".
 */
public class PlayerBotSkillSelector {

	private static final Logger log = LoggerFactory.getLogger(PlayerBotSkillSelector.class);

	private PlayerBotSkillSelector() {
	}

	public static boolean chooseAndCastSkill(BotPlayer bot, Player host) {
		PlayerSkillEntry[] skills = bot.getSkillList().getAllSkills();
		List<Player> group = livingGroupMembers(bot, host);

		// Each of these tiers only returns early on an ACTUAL successful cast - falling through to the
		// next tier (and eventually the ATTACK cascade below) whenever cast() fails, rather than
		// propagating that failure straight out of the whole method. A failed heal/cleanse/buff used to
		// abort the entire tick, which meant a bot whose buffs kept failing (whatever the underlying
		// reason - resources, range, a chain precondition the selector can't see into) never even
		// attempted to attack: it would just cycle through markSkillFailed()'s 4s blacklist on one buff
		// after another forever. Mirrors the fall-through already used for the ATTACK/CHANT loop further
		// down, for the exact same reason. Confirmed live: a Ranger bot stuck retrying buffs
		// indefinitely, "doesn't appear to actually use his bow to attack."
		CastPlan heal = planHeal(bot, skills, group, host);
		if (heal != null && cast(bot, heal.entry, heal.target, "HEAL"))
			return true;

		CastPlan cleanse = planCleanse(bot, skills, group);
		if (cleanse != null && cast(bot, cleanse.entry, cleanse.target, "CLEANSE"))
			return true;

		if (!isHostInCombat(bot, host)) {
			Player deadAlly = findDeadGroupMember(bot, host);
			if (deadAlly != null) {
				PlayerSkillEntry res = findResurrectSkill(bot, skills);
				if (res != null && cast(bot, res, deadAlly, "RES"))
					return true;
			}
		}

		CastPlan buff = planBuff(bot, skills, group);
		if (buff != null && cast(bot, buff.entry, buff.target, "BUFF"))
			return true;

		VisibleObject combatTarget = host.getTarget();
		if (combatTarget instanceof Creature && !((Creature) combatTarget).getLifeStats().isAlreadyDead()
			&& bot.isEnemy((Creature) combatTarget)) {
			// Continuing a combo the bot is already primed for takes priority over the normal
			// highest-rank-first pick below - a chain's later links (SkillTemplate's <chain
			// precategory=.../> startcondition) are frequently a LOWER lvl than unrelated independent
			// skills in the same kit, so ranked-by-level selection alone would never reach them: the bot
			// would keep reopening the chain (or picking something else entirely) instead of following
			// through it. Confirmed live: "the character will always use the first in a chain, never the
			// second, or third if it comes up."
			PlayerSkillEntry chainContinuation = findChainContinuation(bot, skills);
			if (chainContinuation != null && cast(bot, chainContinuation, (Creature) combatTarget, "CHAIN"))
				return true;

			// Try every off-cooldown candidate, best rank first, until one actually lands - not just the
			// single best one. Some "usable" skills (per isActuallyUsable()) are still combo finishers
			// gated on a chain precondition the above chain-continuation check doesn't happen to cover
			// right now (e.g. the bot isn't currently primed for them, or the window already expired -
			// SkillTemplate's <chain precategory=.../> startcondition, tracked via
			// Player.getChainCategory()/getLastChainSkillTime()). If the bot's highest-ranked attack
			// happens to be one of those, it would otherwise get retried forever and nothing else would
			// ever be tried - confirmed live (a Gladiator's best-ranked skill failed silently every ~4s
			// for minutes on end). Falling through to the next-best candidate in the SAME tick means an
			// opener that's actually castable still gets used immediately.
			// Rank order is by skill level alone (rankedUsableSkills), not range - a Cleric's short-range
			// nuke can easily out-rank her actual heal-range attack spell, and trying it first while
			// she's standing at proper casting distance would fail on range, cost her a cast() ->
			// markSkillFailed() 4s blacklist for nothing, and only THEN fall through to the skill she
			// should have used immediately. Skipping anything that can't reach from here means close-
			// range skills get used opportunistically (whenever she genuinely happens to already be in
			// range for some other reason) rather than actively attempted and eating a wasted cooldown
			// every time she's holding proper distance, which is the normal case now that
			// effectiveEngageRange() keeps her back with the mages. Requested live: "the cleric has
			// close range skills, but most are long range + healing. she probably wants to remain far
			// away with the mages in general... the mages also have close range skills that probably
			// want to be ignored unless they are close for some reason."
			List<PlayerSkillEntry> attacks = rankedUsableSkills(bot, skills, SkillSubType.ATTACK);
			if (attacks.isEmpty())
				attacks = rankedUsableSkills(bot, skills, SkillSubType.CHANT);
			for (PlayerSkillEntry attack : attacks) {
				if (!reachesTarget(bot, combatTarget, attack.getSkillTemplate()))
					continue;
				if (cast(bot, attack, (Creature) combatTarget, "ATTACK"))
					return true;
			}

			// Aion skills share cooldowns across whole chains (SkillTemplate.getCooldownId() groups
			// many distinct skills under one ID) - a class's entire attack kit going on cooldown at
			// once after a single cast is normal, not exceptional. Fall back to a basic weapon swing,
			// the same self-throttled (by real attack speed) path real players use via CM_ATTACK, so
			// the bot keeps contributing instead of standing idle until the whole chain clears.
			//
			// BUT: attackTarget() is a melee-range action - Creature's own attack handling closes the
			// distance to use it regardless of how far away PlayerBotAI chose to stand, which would drag
			// a bot whose kit is normally ranged (a Cleric, a Sorcerer/Spirit Master - detected here
			// class-agnostically via effectiveEngageRange(), not a hardcoded class list, matching this
			// file's existing design) right up next to the enemy just to throw one weak swing while its
			// real skills cool down. If the bot has ANY skill that reaches past its own weapon and it
			// isn't already standing in that weapon's range, hold position and let the next tick retry a
			// real skill instead - a melee-only bot (weaponRange == engageRange) is unaffected and keeps
			// swinging exactly as before. Requested live: "the cleric has a melee attack, but probably
			// wants to stay out of direct fighting... if the host is a melee character, the mages will
			// stay close and in turn get close to the enemy."
			float weaponRange = bot.getGameStats().getAttackRange().getCurrent() / 1000f;
			float engageRange = effectiveEngageRange(bot);
			if (engageRange > weaponRange && !MathUtil.isIn3dRange(bot, combatTarget, weaponRange + 1f)) {
				log.info("[bot {}] no usable ATTACK/CHANT skill and kit reaches past weapon range ({} > {}) - holding at range instead of closing to melee",
					bot.getObjectId(), engageRange, weaponRange);
				return false;
			}

			bot.setTarget(combatTarget);
			log.info("[bot {}] no usable ATTACK/CHANT skill, falling back to attackTarget (dist to target={})",
				bot.getObjectId(), MathUtil.getDistance(bot, combatTarget));
			bot.getController().attackTarget((Creature) combatTarget, 0);
			return true;
		}

		return false;
	}

	/** Skill + who/what to target it at, for categories where that varies (group-wide vs. a specific ally). */
	private static final class CastPlan {
		final PlayerSkillEntry entry;
		final VisibleObject target;

		CastPlan(PlayerSkillEntry entry, VisibleObject target) {
			this.entry = entry;
			this.target = target;
		}
	}

	/**
	 * All party/alliance members (including the bot and host) that are actually here to act on -
	 * spawned and alive. Falls back to just bot+host when ungrouped (shouldn't normally happen since
	 * CompanionService always groups a bot with its host, but cheap to be defensive).
	 */
	private static List<Player> livingGroupMembers(BotPlayer bot, Player host) {
		List<Player> members = new ArrayList<Player>();
		PlayerGroup group = host.getPlayerGroup2();
		if (group != null) {
			for (Player member : group.getMembers())
				if (member.isSpawned() && !member.getLifeStats().isAlreadyDead())
					members.add(member);
		}
		else {
			if (!bot.getLifeStats().isAlreadyDead())
				members.add(bot);
			if (!host.getLifeStats().isAlreadyDead())
				members.add(host);
		}
		return members;
	}

	/**
	 * Heals the neediest hurt member of the group, not just the bot or its host - the old version only
	 * ever looked at those two, so a Cleric bot would ignore a dying third party member entirely. Which
	 * skill gets used (and therefore whether one cast heals everyone or just the target) isn't decided
	 * here - see isGroupWide().
	 *
	 * If nobody's hurt badly enough to trigger BOT_HEAL_HP_THRESHOLD's urgent priority AND the host
	 * isn't in combat, falls back to a much more lenient "top back up" pass instead: a bot that just
	 * stops healing entirely at, say, 51% HP the moment a fight ends left the group sitting there
	 * partially hurt indefinitely, since natural regen alone is slow and nothing else was picking up
	 * the difference. Requested live: "the chanter still doesn't heal herself after battle" -
	 * confirmed as expected behavior at the time (75% HP, above the urgent threshold), but the user
	 * then asked for exactly this idle top-off on top of it.
	 */
	private static CastPlan planHeal(BotPlayer bot, PlayerSkillEntry[] skills, List<Player> group, Player host) {
		Player neediest = neediestHurtMember(group, CompanionConfig.BOT_HEAL_HP_THRESHOLD);
		if (neediest == null && !isHostInCombat(bot, host))
			neediest = neediestHurtMember(group, CompanionConfig.BOT_IDLE_HEAL_HP_THRESHOLD);
		if (neediest == null)
			return null;

		PlayerSkillEntry heal = findUsableHealSkill(bot, skills, isHostInCombat(bot, host));
		if (heal == null)
			return null;
		// A party-wide heal auto-applies to every group/alliance member in range once cast with the
		// bot itself as effector (see skillengine/properties/TargetRangeProperty.java's PARTY case) -
		// no need to specifically target the neediest ally for those.
		return new CastPlan(heal, isGroupWide(heal.getSkillTemplate()) ? bot : neediest);
	}

	private static Player neediestHurtMember(List<Player> group, int hpThreshold) {
		Player neediest = null;
		for (Player member : group) {
			if (member.getLifeStats().getHpPercentage() > hpThreshold)
				continue;
			if (neediest == null || member.getLifeStats().getHpPercentage() < neediest.getLifeStats().getHpPercentage())
				neediest = member;
		}
		return neediest;
	}

	/**
	 * Same shape as planHeal() but for debuff removal (Cleric's cleanse-type skills). A member "needs"
	 * cleansing if it has any currently active effect slotted as SkillTargetSlot.DEBUFF - i.e. something
	 * genuinely harmful, not just tracking every buff anyone happens to have up.
	 */
	private static CastPlan planCleanse(BotPlayer bot, PlayerSkillEntry[] skills, List<Player> group) {
		Player afflicted = findAfflictedMember(group);
		if (afflicted == null)
			return null;

		PlayerSkillEntry cleanse = findCleanseSkill(bot, skills);
		if (cleanse == null)
			return null;
		return new CastPlan(cleanse, isGroupWide(cleanse.getSkillTemplate()) ? bot : afflicted);
	}

	private static Player findAfflictedMember(List<Player> group) {
		for (Player member : group)
			if (hasDebuff(member))
				return member;
		return null;
	}

	/**
	 * The AI's movement only ever chases two things: the current combat target, or the host (see
	 * PlayerBotAI.think()) - reasonable for combat, where everyone is naturally clustered around the
	 * fight, but it meant a hurt/afflicted ally who'd simply wandered off (out of combat - exploring,
	 * gathering, etc.) could never actually be reached: the heal/cleanse cast was still attempted every
	 * tick from planHeal()/planCleanse() but silently failed on range forever, since nothing ever moved
	 * the bot toward them. Confirmed live: "she doesn't heal anyone outside of battle". Returns the
	 * group member the bot should walk toward, or null if nothing needs approaching (nobody hurt/
	 * afflicted, everyone already in range, or the bot doesn't know a usable heal/cleanse skill at all).
	 */
	public static Player findSupportApproachTarget(BotPlayer bot, Player host) {
		List<Player> group = livingGroupMembers(bot, host);
		PlayerSkillEntry[] skills = bot.getSkillList().getAllSkills();

		Player hurt = neediestHurtMember(group, CompanionConfig.BOT_HEAL_HP_THRESHOLD);
		if (hurt == null && !isHostInCombat(bot, host))
			hurt = neediestHurtMember(group, CompanionConfig.BOT_IDLE_HEAL_HP_THRESHOLD);
		if (hurt != null && hurt != bot) {
			PlayerSkillEntry heal = findUsableHealSkill(bot, skills, isHostInCombat(bot, host));
			if (heal != null && !isInSkillRange(bot, hurt, heal.getSkillTemplate()))
				return hurt;
		}

		Player afflicted = findAfflictedMember(group);
		if (afflicted != null && afflicted != bot) {
			PlayerSkillEntry cleanse = findCleanseSkill(bot, skills);
			if (cleanse != null && !isInSkillRange(bot, afflicted, cleanse.getSkillTemplate()))
				return afflicted;
		}
		return null;
	}

	/**
	 * The farthest the bot can meaningfully act from right now: its own weapon's basic attack range, or
	 * further if any ATTACK/HEAL/CHANT skill in its kit reaches beyond that (SkillTemplate's own
	 * first_target_range property - the same value {@link #isInSkillRange} validates casts against).
	 * Checked across the bot's WHOLE kit, not just currently off-cooldown skills, so a caster/healer
	 * bot holds its real casting distance even on a tick where its only ranged option happens to be
	 * cooling down, rather than closing to melee and re-opening the gap next tick. Fully class-agnostic
	 * (no PlayerClass check) - a bot with no ranged skills at all just gets its weapon's own range back
	 * unchanged, matching this file's existing design of never hardcoding "this class behaves like X".
	 * Used both by PlayerBotAI to decide how close it needs to walk to engage, and by the melee-fallback
	 * check just above to tell "this bot's kit is genuinely melee-only" apart from "this bot's real
	 * skills are just on cooldown right now". Requested live: "the cleric has a melee attack, but
	 * probably wants to stay out of direct fighting... if the host is a melee character, the mages will
	 * stay close."
	 */
	public static float effectiveEngageRange(BotPlayer bot) {
		float weaponRange = bot.getGameStats().getAttackRange().getCurrent() / 1000f;
		float best = weaponRange;
		for (PlayerSkillEntry entry : bot.getSkillList().getAllSkills()) {
			SkillTemplate template = entry.getSkillTemplate();
			SkillSubType subType = template.getSubType();
			if (subType != SkillSubType.ATTACK && subType != SkillSubType.HEAL && subType != SkillSubType.CHANT)
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

	private static boolean isInSkillRange(BotPlayer bot, Player target, SkillTemplate template) {
		Properties properties = template.getProperties();
		int range = properties != null ? properties.getFirstTargetRange() : 0;
		return range <= 0 || MathUtil.isIn3dRange(bot, target, range);
	}

	/**
	 * Same range check as {@link #isInSkillRange}, generalized to any VisibleObject rather than just a
	 * Player ally - the ATTACK-cascade's combat target is a Creature (the enemy), not necessarily a
	 * Player, so isInSkillRange's signature doesn't fit there directly.
	 */
	private static boolean reachesTarget(BotPlayer bot, VisibleObject target, SkillTemplate template) {
		Properties properties = template.getProperties();
		int range = properties != null ? properties.getFirstTargetRange() : 0;
		return range <= 0 || MathUtil.isIn3dRange(bot, target, range);
	}

	private static boolean hasDebuff(Player player) {
		for (Effect effect : player.getEffectController().getAbnormalEffects())
			if (effect.getTargetSlotEnum() == SkillTargetSlot.DEBUFF)
				return true;
		return false;
	}

	private static PlayerSkillEntry findCleanseSkill(BotPlayer bot, PlayerSkillEntry[] skills) {
		PlayerSkillEntry best = null;
		for (PlayerSkillEntry entry : skills) {
			SkillTemplate template = entry.getSkillTemplate();
			if (template == null || !template.hasCleanseEffect())
				continue;
			if (!isActuallyUsable(bot, template))
				continue;
			best = higherRank(best, entry);
		}
		return best;
	}

	/**
	 * Party-wide buffs are already handled by the plain self-buff check below (findMissingBuff) - cast
	 * with the bot as effector, the engine auto-expands them to the whole group, same mechanism as a
	 * group heal. What's missing without this method is per-ally buffs: single-target buffs the bot
	 * knows but has only ever been applying to itself. This is the Chanter case - some buffs are one
	 * cast for the whole party, others need to be handed out to each character individually.
	 */
	private static CastPlan planBuff(BotPlayer bot, PlayerSkillEntry[] skills, List<Player> group) {
		PlayerSkillEntry selfBuff = findMissingBuff(bot, skills);
		if (selfBuff != null)
			return new CastPlan(selfBuff, bot);

		for (Player member : group) {
			if (member == bot)
				continue;
			PlayerSkillEntry allyBuff = findMissingAllyBuff(bot, skills, member);
			if (allyBuff != null)
				return new CastPlan(allyBuff, member);
		}
		return null;
	}

	private static PlayerSkillEntry findMissingAllyBuff(BotPlayer bot, PlayerSkillEntry[] skills, Player ally) {
		PlayerSkillEntry best = null;
		for (PlayerSkillEntry entry : skills) {
			SkillTemplate template = entry.getSkillTemplate();
			if (template == null || template.getSubType() != SkillSubType.BUFF)
				continue;
			if (isGroupWide(template))
				continue; // already handled once for the whole party via the bot's own self-cast
			if (isSelfOnly(template))
				continue; // can never actually land on an ally - see isSelfOnly()
			if (!isActuallyUsable(bot, template))
				continue;
			if (isBuffAlreadyActive(ally, template))
				continue;
			best = higherRank(best, entry);
		}
		return best;
	}

	/**
	 * True for skills whose Properties.first_target="ME" (FirstTargetAttribute.ME) - the engine always
	 * redirects the effect onto the caster regardless of whatever target was set before casting (see
	 * FirstTargetProperty). Without this check, a self-only stance like the Gladiator's "Aion's
	 * Strength" (skill_id=251) got offered to findMissingAllyBuff() as if it were a distributable
	 * single-target buff: it was cast "at" an ally every tick (useSkill kept returning true because it
	 * always actually lands on the bot, which was already toggled on), while isBuffAlreadyActive() kept
	 * checking the ALLY's effect list - which naturally never has it - so it looked "missing" forever.
	 * Confirmed live: a self-only toggle buff cast continuously at a grouped ally, never actually
	 * affecting them. findMissingBuff() (the self-targeted path) already covers these correctly.
	 */
	private static boolean isSelfOnly(SkillTemplate template) {
		Properties properties = template.getProperties();
		return properties != null && properties.getFirstTarget() == FirstTargetAttribute.ME;
	}

	/**
	 * The set of skills findMissingBuff() should try to keep active on the bot itself: normal
	 * SkillSubType.BUFF entries, plus self-only TOGGLE stances that are classified SkillSubType.CHANT
	 * instead (e.g. Chanter's "Shield Mantra", skill_id=1309-1311/1347 - a defensive aura, not an
	 * attack, despite the CHANT subtype). Excluding these entirely from rankedUsableSkills() (see there)
	 * fixed them being spammed at the enemy via the ATTACK/CHANT fallback, but left them never cast at
	 * all - a real Chanter toggles this on once and leaves it running. Actual attack chants (first_target
	 * != ME) are deliberately NOT included here; they stay attack-only candidates.
	 *
	 * Being subtype BUFF isn't enough on its own though: some "buffs" are actually situational, one-off
	 * cooldown abilities meant to be popped deliberately before something that matters, not maintained
	 * perpetually - Assassin's "Hide" (skill_id=559: 50s duration, 600s/10min cooldown - up ~8% of the
	 * time) or "Clear Focus" (skill_id=813: 60s duration, 1200s/20min cooldown - up ~5% of the time).
	 * Firing these the instant they're off cooldown regardless of what's happening wastes them on
	 * nothing and leaves them unavailable when actually needed. Confirmed live: "Assassins trigger all
	 * their skills immediately... a few others around the place that do not make sense to fire when
	 * idle." canBeKeptPerpetuallyUp() is the class-agnostic filter: only auto-maintain a buff that could
	 * genuinely be kept active 100% of the time by recasting the moment it expires.
	 */
	private static boolean isMaintainableSelfBuff(SkillTemplate template) {
		boolean isBuffLike = template.getSubType() == SkillSubType.BUFF
			|| (template.getSubType() == SkillSubType.CHANT && template.isToggle() && isSelfOnly(template));
		return isBuffLike && canBeKeptPerpetuallyUp(template);
	}

	/**
	 * TOGGLE-activation skills use duration="0" to mean "stays on indefinitely until toggled off", not
	 * "expires instantly" - a fundamentally different pattern from a timed buff, so the duration-vs-
	 * cooldown comparison below doesn't apply to them at all; they're always fair game to maintain.
	 * For a genuine timed buff, it's only "maintainable" if duration >= cooldown - otherwise there will
	 * always be a gap where it's down no matter how promptly it gets recast, meaning it's a periodic
	 * burst ability (see isMaintainableSelfBuff()'s doc), not passive upkeep.
	 */
	private static boolean canBeKeptPerpetuallyUp(SkillTemplate template) {
		// A toggle that's technically "free" to leave on forever isn't actually free if it periodically
		// drains MP/HP to sustain (e.g. Assassin's "Sprinting I", skill_id=912, SKILL_AS_GALEMOVE -
		// a speed buff costing 4 MP every 6s via <periodicactions><mpuse value="4"/></periodicactions>
		// the whole time it's up). A real player only turns this on when they actually want the speed;
		// an AI bot just following/fighting alongside its host has no such need and would otherwise
		// silently bleed MP it needs for heals/attacks/buffs for zero benefit. Checked ahead of the
		// isToggle() short-circuit below since this applies regardless of activation type. Requested
		// live: "the Assassin probably should not use the mana draining skills when in a group like
		// this - specifically the speed boost + whatever other one there is that causes continual mana
		// drain."
		if (hasPeriodicResourceDrain(template))
			return false;
		if (template.isToggle())
			return true;
		// SkillTemplate.getDuration() is the skill CAST's own duration attribute, which is 0 for
		// essentially every buff-application skill - the actual applied-buff duration instead lives on
		// its effect(s) (e.g. <statup duration="30000">), exposed via getEffectsDuration(). This is the
		// exact same fallback Effect.startEffect() itself uses whenever the skill-level duration is
		// unset (skillengine/model/Effect.java: "if (!restored && !forcedDuration) duration =
		// getEffectsDuration();") - comparing against the skill-level field alone made EVERY non-toggle
		// buff with any cooldown at all look unmaintainable (0 >= anything > 0 is always false),
		// wrongly silencing real buffs like the Chanter's own self-buffs. Confirmed live.
		int duration = template.getDuration() > 0 ? template.getDuration() : template.getEffectsDuration();
		// SkillTemplate.cooldown is stored in hundredths of a second, not milliseconds - Creature.java's
		// own cooldown-window check (isSkillDisabled()) multiplies by 100 before comparing against
		// System.currentTimeMillis(); duration is already true milliseconds, so this needs the same
		// conversion to be comparable at all.
		int cooldownMillis = template.getCooldown() * 100;
		return duration >= cooldownMillis;
	}

	private static boolean hasPeriodicResourceDrain(SkillTemplate template) {
		PeriodicActions periodicActions = template.getPeriodicActions();
		if (periodicActions == null || periodicActions.getPeriodicActions() == null)
			return false;
		for (PeriodicAction action : periodicActions.getPeriodicActions())
			if (action instanceof MpUsePeriodicAction || action instanceof HpUsePeriodicAction)
				return true;
		return false;
	}

	/**
	 * True for a skill that, cast once with the bot itself as effector, automatically applies to every
	 * group/alliance member in range - confirmed in skillengine/properties/TargetRangeProperty.java's
	 * PARTY case, which rebuilds the effected list from PlayerGroup.getMembers() regardless of what (if
	 * anything) was targeted. Real data: target_type="PARTY" always pairs with target_relation="MYPARTY"
	 * for both group heals and group buffs (e.g. Cleric's "Healing Wind", Chanter's "Word of Inspiration").
	 */
	private static boolean isGroupWide(SkillTemplate template) {
		Properties properties = template.getProperties();
		return properties != null && properties.getTargetType() == TargetRangeAttribute.PARTY
			&& properties.getTargetRelation() == TargetRelationAttribute.MYPARTY;
	}

	/**
	 * Missing a hostility check originally meant simply having ANY live creature targeted (a vendor, a
	 * quest giver, a harmless nearby mob just clicked on to look at) counted as "in combat" - blocking
	 * the idle heal top-off and resurrect checks below even when nothing was actually happening.
	 * Confirmed live: a 70%-HP ally went unhealed while the user insisted (correctly) that they were
	 * out of combat.
	 */
	private static boolean isHostInCombat(BotPlayer bot, Player host) {
		VisibleObject target = host.getTarget();
		return target instanceof Creature && !((Creature) target).getLifeStats().isAlreadyDead()
			&& bot.isEnemy((Creature) target);
	}

	private static Player findDeadGroupMember(BotPlayer bot, Player host) {
		PlayerGroup group = host.getPlayerGroup2();
		if (group == null)
			return null;
		for (Player member : group.getMembers()) {
			if (member != bot && member.isSpawned() && member.getLifeStats().isAlreadyDead())
				return member;
		}
		return null;
	}

	private static PlayerSkillEntry findResurrectSkill(BotPlayer bot, PlayerSkillEntry[] skills) {
		PlayerSkillEntry best = null;
		for (PlayerSkillEntry entry : skills) {
			SkillTemplate template = entry.getSkillTemplate();
			if (template == null || !template.hasResurrectEffect())
				continue;
			if (!isActuallyUsable(bot, template))
				continue;
			best = higherRank(best, entry);
		}
		return best;
	}

	/**
	 * EffectController.addEffect() evicts the oldest toggle effect the instant a 4th one gets added
	 * (noshowEffects map, capped at 3 - see the "//TODO Gestion MANTRA" eviction rule there). That's a
	 * real class mechanic (a Chanter can only sustain 3 concurrent mantras at once), not a bug - but
	 * findMissingBuff() didn't know about the cap, so once a Chanter had more than 3 maintainable
	 * stances (Shield Mantra, Magic Mantra, etc. - see isMaintainableSelfBuff()) it kept "discovering"
	 * whichever one had just been evicted as missing, recasting it, evicting a different one in turn -
	 * a perpetual 4-way rotation. Confirmed live: "the chanter... is continually spamming buffs" across
	 * 4+ distinct skillIds in a repeating cycle. Once at the cap, leave the existing 3 alone rather than
	 * chase a "better" 4th forever.
	 */
	private static final int MAX_TOGGLE_EFFECTS = 3;

	private static PlayerSkillEntry findMissingBuff(BotPlayer bot, PlayerSkillEntry[] skills) {
		PlayerSkillEntry best = null;
		for (PlayerSkillEntry entry : skills) {
			SkillTemplate template = entry.getSkillTemplate();
			if (template == null || !isMaintainableSelfBuff(template))
				continue;
			if (template.isToggle() && bot.getEffectController().getNoshowEffectCount() >= MAX_TOGGLE_EFFECTS)
				continue;
			if (!isActuallyUsable(bot, template))
				continue;
			if (isBuffAlreadyActive(bot, template))
				continue;
			best = higherRank(best, entry);
		}
		return best;
	}

	/**
	 * Checking only the exact skillId misses rank variants of the same buff (e.g. "Rage I" skillId=161
	 * and "Rage II" skillId=162 both carry stack="SKILL_WA_RAGE") - if the bot has a higher rank already
	 * active, isAbnormalPresentBySkillId(161) still reports "missing", so the selector kept re-picking
	 * skillId 161 forever (and the game engine's own stack-conflict check kept silently rejecting it -
	 * confirmed via live tracing: this was one of the skills stuck permanently failing). Buffs sharing a
	 * stack group are mutually exclusive by design (this is also how Sorcerer's Robe of Fire/Water end
	 * up correctly treated as alternatives rather than both "missing"), so check the whole group via the
	 * effect controller's own stack-keyed lookup instead of a specific skillId.
	 *
	 * That alone still misses a second, separate exclusivity mechanism: Chanter's "Promise of Wind"/
	 * "Promise of Earth" don't share a stack group at all (SKILL_CL_IMBUEWIND vs SKILL_CH_IMBUEEARTH),
	 * but both carry skillset_exception="5" - casting either one auto-removes the other via
	 * EffectController.removeEffectBySetNumber() (controllers/effect/EffectController.java). Without
	 * checking this too, the selector saw the OTHER one as "missing" the instant the first was cast and
	 * flip-flopped between them forever - confirmed live ("the current logic just keeps swapping them").
	 *
	 * A third mechanism entirely: TOGGLE-activation skills (e.g. Gladiator's "Aion's Strength" stance,
	 * skill_id=251, tslot="NOSHOW") don't land in EffectController's abnormalEffectMap at all -
	 * EffectController.getMapForEffect() routes anything with isToggle()==true into a separate
	 * noshowEffects map instead, so getAnormalEffect(stack) always misses them regardless of stack.
	 * Without this check the bot re-toggled the same stance on every tick forever (confirmed live:
	 * "the buff looks to still be continuous"). isNoshowPresentBySkillId() is the matching lookup for
	 * that map (EffectController.java).
	 */
	private static boolean isBuffAlreadyActive(Player player, SkillTemplate template) {
		if (template.isToggle() && player.getEffectController().isNoshowPresentBySkillId(template.getSkillId()))
			return true;

		String stack = template.getStack();
		if (stack != null && !stack.isEmpty() && player.getEffectController().getAnormalEffect(stack) != null)
			return true;

		int setException = template.getSkillSetException();
		if (setException != 0) {
			for (Effect effect : player.getEffectController().getAbnormalEffects())
				if (effect.getSkillSetException() == setException)
					return true;
		}

		if (stack != null && !stack.isEmpty())
			return false;
		return player.getEffectController().isAbnormalPresentBySkillId(template.getSkillId());
	}

	private static PlayerSkillEntry findUsableSkill(BotPlayer bot, PlayerSkillEntry[] skills, SkillSubType subType) {
		PlayerSkillEntry best = null;
		for (PlayerSkillEntry entry : skills) {
			SkillTemplate template = entry.getSkillTemplate();
			if (template == null || template.getSubType() != subType)
				continue;
			if (!isActuallyUsable(bot, template))
				continue;
			best = higherRank(best, entry);
		}
		return best;
	}

	/**
	 * Same as findUsableSkill(..., SkillSubType.HEAL), but when requireCombatSafe is true also skips
	 * anything like "Herb Treatment" (skill_id 1804/1805/1825 - the universal downtime self-heal every
	 * class gets): a ~4s cast, immobile, with cancel_rate="100000" - a sentinel far outside the normal
	 * 0-100 percentage range other skills use, meaning "cancelled unconditionally by the very next hit".
	 * Attempting it while the host is in combat is worse than useless: guaranteed interrupted before it
	 * finishes, for zero effect, while also burning its long (160s) cooldown. Detected generically via
	 * the absurd cancel_rate rather than by skill name/ID, so this naturally covers any other similarly
	 * "downtime-only" heal too. Still perfectly fine (and used) once the fight's actually over - see
	 * planHeal()'s idle top-off pass. Requested live: "We probably do not want to do this in battle."
	 */
	private static PlayerSkillEntry findUsableHealSkill(BotPlayer bot, PlayerSkillEntry[] skills, boolean requireCombatSafe) {
		PlayerSkillEntry best = null;
		for (PlayerSkillEntry entry : skills) {
			SkillTemplate template = entry.getSkillTemplate();
			if (template == null || template.getSubType() != SkillSubType.HEAL)
				continue;
			if (requireCombatSafe && isCombatUnsafeHeal(template))
				continue;
			if (!isActuallyUsable(bot, template))
				continue;
			best = higherRank(best, entry);
		}
		return best;
	}

	private static boolean isCombatUnsafeHeal(SkillTemplate template) {
		return template.getCancelRate() > 100;
	}

	/** Same filtering as findUsableSkill(), but returns every match (highest rank first) instead of just
	 * the single best one - see the ATTACK fallback loop in chooseAndCastSkill() for why. Also excludes
	 * self-only skills: SkillSubType.CHANT is used for a Chanter's actual attack chants but ALSO for
	 * defensive stances like "Shield Mantra" (skill_id=1309-1311/1347, first_target="ME", TOGGLE,
	 * cooldown="0") - those can never land on the ATTACK loop's enemy target regardless of what's cast
	 * at, so without this exclusion the CHANT fallback kept re-toggling a zero-cooldown self-stance at
	 * the enemy every tick forever, since this path (unlike planBuff()) never checked "already active".
	 * Confirmed live: "Shield Mantra is being cast over and over again, where it lasts for 20 min".
	 */
	private static List<PlayerSkillEntry> rankedUsableSkills(BotPlayer bot, PlayerSkillEntry[] skills, SkillSubType subType) {
		List<PlayerSkillEntry> matches = new ArrayList<PlayerSkillEntry>();
		for (PlayerSkillEntry entry : skills) {
			SkillTemplate template = entry.getSkillTemplate();
			if (template == null || template.getSubType() != subType)
				continue;
			if (isSelfOnly(template))
				continue;
			// Chain-continuation skills (SkillTemplate's <chain precategory=.../> startcondition) are
			// exclusively findChainContinuation()'s job, not this generic cascade's - trying one
			// speculatively here, before the bot is actually primed for it, would fail on the
			// precategory check (ChainCondition.validate()) and trigger markSkillFailed()'s 4s backoff,
			// which then blocks the LEGITIMATE attempt once the chain really does get primed a moment
			// later (the chain window itself is only ~5s - see the <chain time=.../> attribute). This
			// was silently starving chain-following entirely: confirmed live, zero "CHAIN" casts ever
			// landed despite the character owning the exact right combo skills.
			ChainCondition chain = getChainCondition(template);
			if (chain != null && chain.getPrecategory() != null)
				continue;
			if (!isActuallyUsable(bot, template))
				continue;
			matches.add(entry);
		}
		Collections.sort(matches, new Comparator<PlayerSkillEntry>() {
			@Override
			public int compare(PlayerSkillEntry a, PlayerSkillEntry b) {
				return b.getSkillTemplate().getLvl() - a.getSkillTemplate().getLvl();
			}
		});
		return matches;
	}

	/**
	 * Multiple ranks of the same skill (Smite I-V, all sharing stack="SKILL_CL_SMITE" and one shared
	 * cooldownId) commonly co-exist in a real character's learned skill list - old ranks aren't removed
	 * when a new one is learned. Picking whichever rank the skill map happens to iterate to first meant
	 * the bot would routinely cast "Smite I" while "Smite V" sat unused - confirmed live. lvl is the
	 * skill's required character level (SkillTemplate.getLvl()), which tracks rank directly: I=1, II=2,
	 * III=3, etc.
	 */
	private static PlayerSkillEntry higherRank(PlayerSkillEntry current, PlayerSkillEntry candidate) {
		if (current == null)
			return candidate;
		return candidate.getSkillTemplate().getLvl() > current.getSkillTemplate().getLvl() ? candidate : current;
	}

	/**
	 * A skill the bot is CURRENTLY primed to continue into, per Player.getChainCategory()/
	 * getLastChainSkillTime() (set on the Player by Skill.java on any successful cast of a
	 * <chain>-tagged skill - see ChainCondition.java). Mirrors ChainCondition.validate()'s own
	 * precategory/expiry check exactly, so this only ever returns something the engine would
	 * actually accept - checked ahead of the normal ranked-by-level pick in chooseAndCastSkill() so
	 * a combo actually gets followed through (its later links are frequently a LOWER lvl than
	 * unrelated independent skills, so ranked-by-level selection alone would never reach them)
	 * instead of the bot reopening the same chain, or picking something unrelated, every tick.
	 */
	private static PlayerSkillEntry findChainContinuation(BotPlayer bot, PlayerSkillEntry[] skills) {
		String currentChain = bot.getChainCategory();
		if (currentChain == null)
			return null;
		long now = System.currentTimeMillis();
		PlayerSkillEntry best = null;
		for (PlayerSkillEntry entry : skills) {
			SkillTemplate template = entry.getSkillTemplate();
			if (template == null
				|| (template.getSubType() != SkillSubType.ATTACK && template.getSubType() != SkillSubType.CHANT))
				continue;
			ChainCondition chain = getChainCondition(template);
			if (chain == null || !currentChain.equals(chain.getPrecategory()))
				continue;
			if (bot.getLastChainSkillTime() + chain.getTime() < now)
				continue;
			if (!isActuallyUsable(bot, template))
				continue;
			best = higherRank(best, entry);
		}
		return best;
	}

	private static ChainCondition getChainCondition(SkillTemplate template) {
		Conditions startconditions = template.getStartconditions();
		if (startconditions == null)
			return null;
		for (Condition condition : startconditions.getConditions())
			if (condition instanceof ChainCondition)
				return (ChainCondition) condition;
		return null;
	}

	/**
	 * Off-cooldown alone doesn't mean castable: many Aion skills are combo finishers gated on
	 * {@link SkillTemplate#getCounterSkill()} - only usable within 5s of the caster having actually
	 * landed the matching DODGE/PARRY/BLOCK reaction (see Skill.canUseSkill()'s identical check,
	 * skillengine/model/Skill.java). A rotation picker with no combo-sequencing has no way to have set
	 * that up on purpose, so treating such a skill as "usable" just means useSkill() silently fails
	 * every tick forever - confirmed via live tracing (bot repeatedly retried the same skill, always
	 * failing). Skip these unless the window happens to be open (e.g. the bot's own gear/stats just
	 * produced a real dodge/parry/block against an incoming attack).
	 */
	private static boolean isActuallyUsable(BotPlayer bot, SkillTemplate template) {
		if (bot.isSkillDisabled(template))
			return false;
		if (bot.isSkillOnFailureCooldown(template.getSkillId()))
			return false;
		AttackStatus counterSkill = template.getCounterSkill();
		if (counterSkill != null) {
			long since = System.currentTimeMillis() - bot.getLastCounterSkill(counterSkill);
			if (since > 5000)
				return false;
		}
		return true;
	}

	private static boolean cast(BotPlayer bot, PlayerSkillEntry entry, VisibleObject target, String kind) {
		bot.setTarget(target);
		boolean success = bot.getController().useSkill(entry.getSkillId(), entry.getSkillLevel());
		if (!success) {
			// useSkill() failing silently can mean any number of things this selector can't see into
			// (an Aion combo-chain precondition tracked via Player.getChainCategory(), insufficient
			// resources, range/LoS) - whatever the cause, retrying the exact same skill next tick would
			// too, so back off it briefly and let the selector try something else meanwhile.
			bot.markSkillFailed(entry.getSkillId());
		}
		log.info("[bot {}] {} skillId={} level={} target={} -> useSkill={}",
			bot.getObjectId(), kind, entry.getSkillId(), entry.getSkillLevel(),
			target == bot ? "self" : target.getObjectId(), success);
		return success;
	}
}
