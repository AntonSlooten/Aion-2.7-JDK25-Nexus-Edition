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
package com.aionemu.gameserver.skillengine.condition;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.skillengine.model.Skill;


/**
 * @author ATracer
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "ChainCondition")
public class ChainCondition extends Condition {

	@XmlAttribute(name = "category")
	private String category;
	@XmlAttribute(name = "precategory")
	private String precategory;
	@XmlAttribute(name = "time")
	private int time;

	@Override
	public boolean validate(Skill env) {
		
		if (env.getEffector() instanceof Player && precategory != null)
		{
			Player pl = (Player)env.getEffector();
			if (precategory.equals(pl.getChainCategory())) {
				if ((pl.getLastChainSkillTime() + time) < System.currentTimeMillis())
					//TODO: log for catching cheaters?
					return false;
			}
			else
				return false;
		}
		
		env.setChainCategory(category);
		return true;
	}

	/**
	 * Read-only accessors added so external callers (companion bot AI - see PlayerBotSkillSelector's
	 * chain-continuation logic) can inspect a skill's combo linkage without re-implementing this
	 * class's own validate() logic. category/precategory/time otherwise stay package-private XML-bound
	 * fields with no behavior change.
	 */
	public String getCategory() {
		return category;
	}

	public String getPrecategory() {
		return precategory;
	}

	public int getTime() {
		return time;
	}

}
