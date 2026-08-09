package com.gielinorcartography;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * Ranks every player by total points, filterable to one exclusive total-level tier or "Global"
 * (everyone). Defaults to the viewer's own tier the first time real data arrives, then leaves
 * the selection alone so browsing a different tier doesn't keep getting reset. update() is only
 * ever called from the Swing EDT - the plugin is responsible for that guarantee, not this class.
 * A tab's content inside GielinorCartographyPanel, not a top-level sidebar panel itself.
 */
class GielinorCartographyLeaderboardPanel extends JPanel
{
	private static final String GLOBAL = "Global";

	private final JComboBox<String> tierFilter;
	private final JPanel rowContainer = new JPanel();

	private List<PlayerRanking> lastRankings = Collections.emptyList();
	private String lastLocalPlayerName;
	private boolean hasSetDefaultTier;

	GielinorCartographyLeaderboardPanel()
	{
		tierFilter = new JComboBox<>(tierOptions());
		tierFilter.addActionListener(e -> render());

		rowContainer.setLayout(new BoxLayout(rowContainer, BoxLayout.Y_AXIS));

		setLayout(new BorderLayout(0, 6));
		add(tierFilter, BorderLayout.NORTH);
		add(rowContainer, BorderLayout.CENTER);
	}

	private static String[] tierOptions()
	{
		LeaderboardTier[] tiers = LeaderboardTier.values();
		String[] options = new String[tiers.length + 1];
		options[0] = GLOBAL;
		for (int i = 0; i < tiers.length; i++)
		{
			options[i + 1] = tiers[i].label;
		}
		return options;
	}

	void update(List<PlayerRanking> rankings, String localPlayerName, Integer localTotalLevel)
	{
		lastRankings = rankings;
		lastLocalPlayerName = localPlayerName;

		if (!hasSetDefaultTier && localTotalLevel != null)
		{
			tierFilter.setSelectedItem(LeaderboardTier.of(localTotalLevel).label);
			hasSetDefaultTier = true;
		}

		render();
	}

	private void render()
	{
		rowContainer.removeAll();

		String selectedTier = (String) tierFilter.getSelectedItem();

		// Already sorted by total points descending by the backend - filtering preserves order.
		List<PlayerRanking> filtered = new ArrayList<>();
		for (PlayerRanking ranking : lastRankings)
		{
			if (GLOBAL.equals(selectedTier)
				|| (ranking.totalLevel != null && LeaderboardTier.of(ranking.totalLevel).label.equals(selectedTier)))
			{
				filtered.add(ranking);
			}
		}

		int rank = 1;
		for (PlayerRanking ranking : filtered)
		{
			boolean isYou = lastLocalPlayerName != null && lastLocalPlayerName.equals(ranking.name);
			rowContainer.add(buildRow(rank, ranking, isYou));
			rowContainer.add(Box.createVerticalStrut(2));
			rank++;
		}
		// Without this, BoxLayout would stretch the last (or only) row to fill any leftover
		// vertical space instead of leaving it empty below the rows, which is what made a single
		// entry look like it was floating in the middle of the panel.
		rowContainer.add(Box.createVerticalGlue());

		rowContainer.revalidate();
		rowContainer.repaint();
	}

	private static JPanel buildRow(int rank, PlayerRanking ranking, boolean isYou)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setAlignmentX(0f);

		Color textColor = isYou ? ColorScheme.BRAND_ORANGE : Color.WHITE;

		JLabel rankLabel = new JLabel("#" + rank);
		rankLabel.setForeground(textColor);

		JLabel nameLabel = new JLabel(ranking.name + " — " + ranking.totalPoints + " pts");
		nameLabel.setForeground(textColor);

		row.add(rankLabel, BorderLayout.WEST);
		row.add(nameLabel, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}
}
