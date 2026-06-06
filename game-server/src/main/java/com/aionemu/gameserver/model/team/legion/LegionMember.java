package com.aionemu.gameserver.model.team.legion;

/**
 * @author Simple
 */
public class LegionMember {

	private int objectId;
	protected Legion legion;
	protected String nickname = "";
	protected String selfIntro = "";

	protected LegionRank rank = LegionRank.VOLUNTEER;

	/**
	 * If player is defined later on this constructor is called
	 */
	public LegionMember(int objectId) {
		this.objectId = objectId;
	}

	/**
	 * This constructor is called when a legion is created
	 */
	public LegionMember(int objectId, Legion legion, LegionRank rank) {
		this.objectId = objectId;
		this.legion = legion;
		this.rank = rank;
	}

	/**
	 * This constructor is called when a LegionMemberEx is called
	 */
	public LegionMember() {
	}

	public void setLegion(Legion legion) {
		this.legion = legion;
	}

	public Legion getLegion() {
		return legion;
	}

	public void setRank(LegionRank rank) {
		this.rank = rank;
	}

	public LegionRank getRank() {
		return rank;
	}

	public boolean isBrigadeGeneral() {
		return rank == LegionRank.BRIGADE_GENERAL;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public String getNickname() {
		return nickname;
	}

	public void setSelfIntro(String selfIntro) {
		this.selfIntro = selfIntro;
	}

	public String getSelfIntro() {
		return selfIntro;
	}

	public void setObjectId(int objectId) {
		this.objectId = objectId;
	}

	public int getObjectId() {
		return objectId;
	}

	public boolean hasRights(LegionPermissionsMask permissions) {
		int legionarPermission = 0;

		switch (getRank()) {
		case BRIGADE_GENERAL:
			return true;
		case DEPUTY:
			legionarPermission = legion.getDeputyPermission();
			break;
		case CENTURION:
			legionarPermission = legion.getCenturionPermission();
			break;
		case LEGIONARY:
			legionarPermission = legion.getLegionaryPermission();
			break;
		case VOLUNTEER:
			legionarPermission = legion.getVolunteerPermission();
			break;
		}

		return permissions.can(legionarPermission);
	}
}