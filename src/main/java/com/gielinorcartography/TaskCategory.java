package com.gielinorcartography;

/**
 * A task's category is entirely derived from its completion type - no separate schema field,
 * since production and gathering tasks (both CHAT_MESSAGE_COUNT) are deliberately lumped
 * together as "Skilling" rather than needing a way to tell them apart.
 */
enum TaskCategory
{
	COMBAT("Combat"),
	SKILLING("Skilling"),
	STAND_IN_ZONE("Stand in Zone"),
	AGILITY("Agility");

	final String label;

	TaskCategory(String label)
	{
		this.label = label;
	}

	static TaskCategory of(TaskDefinition.CompletionType completionType)
	{
		switch (completionType)
		{
			case NPC_LOOT_COUNT:
				return COMBAT;
			case CHAT_MESSAGE_COUNT:
				return SKILLING;
			case HOLD:
				return STAND_IN_ZONE;
			case LOCATION_STAT_COUNT:
				return AGILITY;
			default:
				throw new IllegalStateException("Unhandled completion type: " + completionType);
		}
	}

	@Override
	public String toString()
	{
		return label;
	}
}
