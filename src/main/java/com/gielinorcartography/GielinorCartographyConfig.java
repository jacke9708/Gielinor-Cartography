package com.gielinorcartography;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gielinor-cartography")
public interface GielinorCartographyConfig extends Config
{
	@ConfigItem(
		keyName = "serverUrl",
		name = "Server URL",
		description = "Base URL of the Gielinor Cartography backend. LAN-only for now, no auth."
	)
	default String serverUrl()
	{
		// Points at the LAN Linux server hosting the backend (2026-08-09) - was 127.0.0.1 while
		// the backend ran on the same dev machine as the client. If you saved a value in the
		// config panel before this changed, it won't pick up this new default automatically -
		// update it there manually.
		return "http://192.168.50.170:8000";
	}

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
