import { describe, it, expect } from 'vitest'
import { compareScanRows } from './scanRowSort.js'

// ── helpers ──────────────────────────────────────────────────────────────────

const row = (ticker) => ({ ticker })

const scores = (...pairs) => {
  const m = {}
  for (let i = 0; i < pairs.length; i += 2) m[pairs[i]] = { fundamentalScore: 0, memoryScore: 0, hybridScore: pairs[i + 1] }
  return m
}

/** Sort an array of ticker strings using compareScanRows. */
const sort = (tickers, hotList, scoresMap) =>
  [...tickers].map(row).sort((a, b) => compareScanRows(a, b, hotList, scoresMap)).map((r) => r.ticker)

// ── test cases ───────────────────────────────────────────────────────────────

describe('compareScanRows', () => {

  it('HOT-first preserves hotList insertion order', () => {
    // hotList: NVDA at 0, AAPL at 1 → NVDA must appear before AAPL
    // non-HOT: MSFT (hybrid 0.9) and GOOG (hybrid 0.5) come after, sorted desc by score
    const result = sort(
      ['AAPL', 'MSFT', 'NVDA', 'GOOG'],
      ['NVDA', 'AAPL'],
      scores('MSFT', 0.9, 'GOOG', 0.5),
    )
    expect(result).toEqual(['NVDA', 'AAPL', 'MSFT', 'GOOG'])
  })

  it('non-HOT rows sorted by hybridScore descending', () => {
    const result = sort(
      ['A', 'B', 'C'],
      [],
      scores('A', 0.3, 'B', 0.7, 'C', 0.5),
    )
    expect(result).toEqual(['B', 'C', 'A'])
  })

  it('ties broken alphabetically (ticker.localeCompare)', () => {
    const result = sort(
      ['B', 'A'],
      [],
      scores('A', 0.5, 'B', 0.5),
    )
    expect(result).toEqual(['A', 'B'])
  })

  it('missing scores treated as 0 — row with score comes first', () => {
    // A has hybridScore 0.4; B is missing → treated as 0 → A before B
    const result = sort(
      ['A', 'B'],
      [],
      scores('A', 0.4),
    )
    expect(result).toEqual(['A', 'B'])
  })

  it('only a is HOT → a before b (non-HOT)', () => {
    const result = sort(['B', 'A'], ['A'], scores('B', 0.99))
    expect(result).toEqual(['A', 'B'])
  })

  it('only b is HOT → b before a (non-HOT)', () => {
    const result = sort(['A', 'B'], ['B'], scores('A', 0.99))
    expect(result).toEqual(['B', 'A'])
  })

  it('both rows are HOT at same index → treated as equal (0), stable via localeCompare fallback', () => {
    // NVDA and AAPL both at different indices → order must match hotList
    const result = sort(['AAPL', 'NVDA'], ['NVDA', 'AAPL'], {})
    expect(result).toEqual(['NVDA', 'AAPL'])
  })

  it('both rows are HOT — index ordering is preserved regardless of hybridScore', () => {
    // AAPL is index 0, NVDA is index 1 → AAPL first even though NVDA has higher score
    const result = sort(
      ['NVDA', 'AAPL'],
      ['AAPL', 'NVDA'],
      scores('NVDA', 0.99, 'AAPL', 0.01),
    )
    expect(result).toEqual(['AAPL', 'NVDA'])
  })

})
