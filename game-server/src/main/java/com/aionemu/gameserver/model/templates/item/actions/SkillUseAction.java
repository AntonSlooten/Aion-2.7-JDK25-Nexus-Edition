/*
 * This file is part of aion-unique <www.aion-unique.com>.
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
package com.aionemu.gameserver.model.templates.item.actions;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;

import com.aionemu.gameserver.model.DescriptionId;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_SYSTEM_MESSAGE;
import com.aionemu.gameserver.questEngine.QuestEngine;
import com.aionemu.gameserver.questEngine.model.QuestEnv;
import com.aionemu.gameserver.skillengine.SkillEngine;
import com.aionemu.gameserver.skillengine.effect.EffectTemplate;
import com.aionemu.gameserver.skillengine.effect.TransformEffect;
import com.aionemu.gameserver.skillengine.model.Skill;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.FirstTargetAttribute;
import com.aionemu.gameserver.skillengine.properties.Properties;
import com.aionemu.gameserver.utils.PacketSendUtility;

/**
 * @author ATracer
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SkillUseAction")
public class SkillUseAction extends AbstractItemAction {

	@XmlAttribute
	protected int skillid;
	@XmlAttribute
	protected int level;

	/**
	 * Gets the value of the skillid property.
	 */
	public int getSkillid() {
		return skillid;
	}

	/**
	 * Gets the value of the level property.
	 */
	public int getLevel() {
		return level;
	}

	@Override
	public boolean canAct(Player player, Item parentItem, Item targetItem) {
		Skill skill = SkillEngine.getInstance().getSkill(player, skillid, level, player.getTarget(),
			parentItem.getItemTemplate());
		if (skill == null)
			return false;
		// Cant use transform items while already transformed
		if (player.isTransformed()) {
			for (EffectTemplate template : skill.getSkillTemplate().getEffects().getEffects()) {
				if (template instanceof TransformEffect) {
					PacketSendUtility.sendPacket(player,
						SM_SYSTEM_MESSAGE.STR_CANT_USE_ITEM(new DescriptionId(parentItem.getItemTemplate().getNameId())));
					return false;
				}
			}
		}
			
		return skill.canUseSkill();
	}

	@Override
	public void act(Player player, Item parentItem, Item targetItem) {
		Skill skill = SkillEngine.getInstance().getSkill(player, skillid, level, player.getTarget(),
			parentItem.getItemTemplate());
		if (skill != null) {
			player.getController().cancelUseItem();
			skill.setItemObjectId(parentItem.getObjectId());
			skill.useSkill();
			QuestEnv env = new QuestEnv(player.getTarget(), player, 0, 0);
			QuestEngine.getInstance().onUseSkill(env, skillid);
			shareWithCompanions(player, skill.getSkillTemplate());
		}
	}

	/**
	 * Extends a consumable's effect to every companion bot in the host's group - food, drinks, and
	 * scrolls (speed buffs, stat food, etc.) are all things a real party would naturally each carry
	 * their own copy of and use together, and bots have no inventory/client of their own to do that.
	 * Reuses CreatureController.useSkill(skillId, skillLevel) - the same mechanism bots already use for
	 * every other skill they cast (PlayerBotSkillSelector.cast()) - rather than reusing this specific
	 * Skill instance, since that one is already bound to the host as effector.
	 *
	 * Excludes anything with hasInstantHealEffect() (health potions, mana potions, flight recharge
	 * potions - see that method's own note on the data signature) and anything not self-targeted
	 * (first_target=ME, the shape every real consumable buff/food/drink/scroll actually has) - both
	 * explicitly requested: "this should not work with health/mana/flight pots."
	 *
	 * Skips a bot that's currently mid-cast rather than force it through: Skill.canUseSkill() has no
	 * "already casting" guard at all (a known, separately-documented engine bug - see
	 * PlayerBotAI.think()'s own isCasting() guard for the fix on the bot's own AI loop), so calling
	 * useSkill() on a casting bot here would silently stomp whatever it was already casting. Missing
	 * one application of a food/scroll buff because of bad timing is a minor miss; interrupting a
	 * multi-second heal cast is not.
	 */
	private void shareWithCompanions(Player player, SkillTemplate template) {
		if (template.hasInstantHealEffect())
			return;
		Properties properties = template.getProperties();
		if (properties == null || properties.getFirstTarget() != FirstTargetAttribute.ME)
			return;
		for (Player bot : player.getBots()) {
			if (!bot.isCasting())
				bot.getController().useSkill(skillid, level);
		}
	}

}
