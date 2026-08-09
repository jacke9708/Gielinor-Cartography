package com.gielinorcartography;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gielinor-cartography")
public interface GielinorCartographyConfig extends Config
{
	@ConfigItem(
		keyName = "stolenTaskChatNotification",
		name = "Chat message when a task is stolen from you",
		description = "Sends a chat message when a task you own gets claimed by someone else."
	)
	default boolean stolenTaskChatNotification()
	{
		return true;
	}

	@ConfigItem(
		keyName = "stolenTaskWindowsNotification",
		name = "System notification when a task is stolen from you",
		description = "Shows an OS-level notification (per your RuneLite Notifications settings) when a task you own gets claimed by someone else."
	)
	default boolean stolenTaskWindowsNotification()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMapMarkers",
		name = "Show task markers on the world map",
		description = "Places a colored dot on the world map at each task's location."
	)
	default boolean showMapMarkers()
	{
		return true;
	}
}
