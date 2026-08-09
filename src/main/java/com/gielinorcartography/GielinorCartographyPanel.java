package com.gielinorcartography;

import java.util.function.Consumer;
import javax.swing.JTabbedPane;
import net.runelite.client.ui.PluginPanel;

/**
 * The one sidebar tab this plugin registers - a tabbed pane holding the tasks list and the
 * leaderboard as separate tabs, rather than two independent sidebar icons.
 */
class GielinorCartographyPanel extends PluginPanel
{
	private final GielinorCartographyTasksPanel tasksPanel;
	private final GielinorCartographyLeaderboardPanel leaderboardPanel;

	GielinorCartographyPanel(Consumer<TaskDefinition> onShowOnMap)
	{
		tasksPanel = new GielinorCartographyTasksPanel(onShowOnMap);
		leaderboardPanel = new GielinorCartographyLeaderboardPanel();

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Tasks", tasksPanel);
		tabs.addTab("Leaderboard", leaderboardPanel);

		add(tabs);
	}

	GielinorCartographyTasksPanel tasks()
	{
		return tasksPanel;
	}

	GielinorCartographyLeaderboardPanel leaderboard()
	{
		return leaderboardPanel;
	}
}
