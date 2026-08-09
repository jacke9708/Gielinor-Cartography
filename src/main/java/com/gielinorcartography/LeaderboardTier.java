package com.gielinorcartography;

/**
 * Exclusive total-level brackets (not cumulative like real OSRS "Total Level" worlds - a
 * player falls into exactly one, based on their last-known total level) plus a "Global" view
 * spanning everyone. Named after the real total-level-world convention (each label is the
 * bracket's upper bound) for familiarity.
 */
enum LeaderboardTier
{
	UP_TO_500(0, 499, "0-499"),
	UP_TO_750(500, 749, "500-749"),
	UP_TO_1250(750, 1249, "750-1249"),
	UP_TO_1500(1250, 1499, "1250-1499"),
	UP_TO_1750(1500, 1749, "1500-1749"),
	UP_TO_2000(1750, 1999, "1750-1999"),
	UP_TO_2200(2000, 2199, "2000-2199"),
	UP_TO_2350(2200, 2349, "2200-2349"),
	OVER_2350(2350, Integer.MAX_VALUE, "2350+");

	final int minInclusive;
	final int maxInclusive;
	final String label;

	LeaderboardTier(int minInclusive, int maxInclusive, String label)
	{
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
		this.label = label;
	}

	boolean contains(int totalLevel)
	{
		return totalLevel >= minInclusive && totalLevel <= maxInclusive;
	}

	static LeaderboardTier of(int totalLevel)
	{
		for (LeaderboardTier tier : values())
		{
			if (tier.contains(totalLevel))
			{
				return tier;
			}
		}

		// Every int is covered by some tier (OVER_2350 has no upper bound), so this is
		// unreachable - only negative totalLevel could get here, which never happens in practice.
		return OVER_2350;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
