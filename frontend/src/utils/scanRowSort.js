/**
 * Pure comparator for scan activity rows.
 *
 * Ordering rules (applied in priority order):
 *   1. HOT tickers come first, sorted by their position in hotList.
 *   2. Non-HOT rows sorted by hybridScore descending.
 *   3. Ties resolved alphabetically by ticker (localeCompare).
 *
 * @param {{ ticker: string }} a
 * @param {{ ticker: string }} b
 * @param {string[]} hotList  - ordered list of HOT tickers (case-sensitive match)
 * @param {Record<string, { hybridScore: number }>} scores - scan score map; missing entries treated as 0
 * @returns {number} negative | 0 | positive
 */
export function compareScanRows(a, b, hotList, scores) {
  const aHot = hotList.indexOf(a.ticker)
  const bHot = hotList.indexOf(b.ticker)

  // Both HOT → sort by hotList position (ascending index = higher priority)
  if (aHot >= 0 && bHot >= 0) return aHot - bHot

  // Only a is HOT → a comes first
  if (aHot >= 0) return -1

  // Only b is HOT → b comes first
  if (bHot >= 0) return 1

  // Neither HOT → sort by hybridScore descending
  const aScore = scores[a.ticker]?.hybridScore ?? 0
  const bScore = scores[b.ticker]?.hybridScore ?? 0

  if (aScore !== bScore) return bScore - aScore

  // Tie → alphabetical by ticker
  return a.ticker.localeCompare(b.ticker)
}
