package com.gielinorcartography;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Lists every task with its current status, filterable by category/region. update() is only
 * ever called from the Swing EDT - the plugin is responsible for that guarantee, not this class.
 * A tab's content inside GielinorCartographyPanel, not a top-level sidebar panel itself.
 */
class GielinorCartographyTasksPanel extends JPanel
{
	// Must match backend/server.py's PASSIVE_POINTS_PER_MINUTE - duplicated rather than fetched,
	// same as the REGIONS list below is duplicated from wiki.py's REGION_MAP.
	private static final double PASSIVE_POINTS_PER_MINUTE = 0.1;

	private static final String ALL_CATEGORIES = "All Categories";
	private static final String ALL_REGIONS = "All Regions";
	// The plugin's canonical 13-region set - matches wiki.py's REGION_MAP output exactly, so
	// every task.region value is guaranteed to be one of these.
	private static final String[] REGIONS = {
		"Asgarnia", "Fremennik Province", "Great Kourend", "Kandarin", "Karamja",
		"Kebos Lowlands", "Kharidian Desert", "Misthalin", "Morytania", "Tirannwn",
		"Varlamore", "Wilderness", "Other",
	};

	private final JComboBox<String> categoryFilter;
	private final JComboBox<String> regionFilter;
	private final JLabel ownedCountLabel = new JLabel();
	private final JPanel rowContainer = new JPanel();
	// Only touches the real world map through Client - this panel has no Client dependency of
	// its own, the plugin supplies the actual behavior.
	private final Consumer<TaskDefinition> onShowOnMap;

	// Cached from the last update() call, so changing a filter can re-render without waiting on
	// (or triggering) a fresh network fetch.
	private List<TaskDefinition> lastTasks = Collections.emptyList();
	private Map<String, TaskState> lastStatesById = Collections.emptyMap();
	private String lastLocalPlayerName;

	GielinorCartographyTasksPanel(Consumer<TaskDefinition> onShowOnMap)
	{
		this.onShowOnMap = onShowOnMap;

		categoryFilter = new JComboBox<>(categoryOptions());
		regionFilter = new JComboBox<>(regionOptions());
		categoryFilter.addActionListener(e -> render());
		regionFilter.addActionListener(e -> render());

		JPanel filterPanel = new JPanel(new GridLayout(2, 1, 0, 2));
		filterPanel.add(categoryFilter);
		filterPanel.add(regionFilter);

		ownedCountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		ownedCountLabel.setBorder(new EmptyBorder(0, 2, 4, 2));

		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.add(ownedCountLabel, BorderLayout.NORTH);
		headerPanel.add(filterPanel, BorderLayout.CENTER);

		rowContainer.setLayout(new BoxLayout(rowContainer, BoxLayout.Y_AXIS));

		setLayout(new BorderLayout(0, 6));
		add(headerPanel, BorderLayout.NORTH);
		add(rowContainer, BorderLayout.CENTER);
	}

	private static String[] categoryOptions()
	{
		String[] options = new String[TaskCategory.values().length + 1];
		options[0] = ALL_CATEGORIES;
		int i = 1;
		for (TaskCategory category : TaskCategory.values())
		{
			options[i++] = category.label;
		}
		return options;
	}

	private static String[] regionOptions()
	{
		String[] options = new String[REGIONS.length + 1];
		options[0] = ALL_REGIONS;
		System.arraycopy(REGIONS, 0, options, 1, REGIONS.length);
		return options;
	}

	void update(List<TaskDefinition> tasks, Map<String, TaskState> statesById, String localPlayerName)
	{
		lastTasks = tasks;
		lastStatesById = statesById;
		lastLocalPlayerName = localPlayerName;
		render();
	}

	private void render()
	{
		rowContainer.removeAll();

		String selectedCategory = (String) categoryFilter.getSelectedItem();
		String selectedRegion = (String) regionFilter.getSelectedItem();

		long now = System.currentTimeMillis() / 1000;

		// Counted across every task regardless of the current filter selection - this is meant
		// to read as "your total territory", not "how many of the currently visible rows are
		// yours".
		int ownedCount = 0;
		for (TaskDefinition task : lastTasks)
		{
			TaskState ownedState = lastStatesById.get(task.id);
			if (ownedState != null && TaskStatus.compute(ownedState, lastLocalPlayerName, now) == TaskStatus.OWNED_BY_YOU)
			{
				ownedCount++;
			}
		}
		double pointsPerMinute = ownedCount * PASSIVE_POINTS_PER_MINUTE;
		ownedCountLabel.setText(String.format("Owned: %d / %d  •  +%.1f pts/min", ownedCount, lastTasks.size(), pointsPerMinute));

		List<Row> rows = new ArrayList<>();
		for (TaskDefinition task : lastTasks)
		{
			if (!ALL_CATEGORIES.equals(selectedCategory) && !TaskCategory.of(task.completionType).label.equals(selectedCategory))
			{
				continue;
			}
			if (!ALL_REGIONS.equals(selectedRegion) && !task.region.equals(selectedRegion))
			{
				continue;
			}

			TaskState state = lastStatesById.get(task.id);
			TaskStatus status = state == null ? TaskStatus.UNCLAIMED : TaskStatus.compute(state, lastLocalPlayerName, now);
			rows.add(new Row(task, status, state));
		}
		// Owned-by-you tasks first (stable sort, so ties keep tasks.json's original order) - the
		// tasks you're actually managing right now are more useful up top than buried among
		// however many hundred others are unclaimed/locked.
		rows.sort(Comparator.comparing(row -> row.status != TaskStatus.OWNED_BY_YOU));

		for (Row row : rows)
		{
			rowContainer.add(buildRow(row.task, row.status, row.state, onShowOnMap));
			rowContainer.add(Box.createVerticalStrut(4));
		}
		// Without this, BoxLayout would stretch the last row to fill any leftover vertical space
		// instead of leaving it empty below the rows.
		rowContainer.add(Box.createVerticalGlue());

		rowContainer.revalidate();
		rowContainer.repaint();
	}

	private static JPanel buildRow(TaskDefinition task, TaskStatus status, TaskState state, Consumer<TaskDefinition> onShowOnMap)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setAlignmentX(0f);

		JLabel dot = new JLabel("●");
		dot.setForeground(status.color());

		JPanel textStack = new JPanel(new GridLayout(2, 1));
		textStack.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel name = new JLabel(task.displayName);
		name.setForeground(Color.WHITE);
		name.setToolTipText(task.displayName);
		textStack.add(name);

		String detailText = task.objectiveSummary() + " • " + describeStatus(status, state);
		JLabel detail = new JLabel(detailText);
		detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		detail.setFont(FontManager.getRunescapeSmallFont());
		detail.setToolTipText(detailText);
		textStack.add(detail);

		JButton showOnMapButton = new JButton("Map");
		showOnMapButton.setToolTipText("Focus the world map on " + task.displayName + " (opens next time you open the map, if it's closed now)");
		showOnMapButton.addActionListener(e -> onShowOnMap.accept(task));

		row.add(dot, BorderLayout.WEST);
		row.add(textStack, BorderLayout.CENTER);
		row.add(showOnMapButton, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static final class Row
	{
		final TaskDefinition task;
		final TaskStatus status;
		final TaskState state;

		Row(TaskDefinition task, TaskStatus status, TaskState state)
		{
			this.task = task;
			this.status = status;
			this.state = state;
		}
	}

	private static String describeStatus(TaskStatus status, TaskState state)
	{
		switch (status)
		{
			case UNCLAIMED:
				return "Unclaimed";
			case OWNED_BY_YOU:
				// Cooldown has no bearing on the color (still green either way, per TaskStatus),
				// but once it's expired you can re-claim your own task to bank its passive
				// income and collect another flat claim reward - worth calling out here.
				if (state.lastTaken != null && System.currentTimeMillis() / 1000 >= state.lastTaken + state.cooldownSeconds)
				{
					return "Owned by you (claimable)";
				}
				return "Owned by you";
			case AVAILABLE:
				return "Available (was " + state.owner + ")";
			case LOCKED:
				long remainingSeconds = Math.max((state.lastTaken + state.cooldownSeconds) - System.currentTimeMillis() / 1000, 0);
				return "Locked by " + state.owner + " (" + (remainingSeconds / 60) + "m left)";
			default:
				throw new IllegalStateException("Unhandled status: " + status);
		}
	}
}
