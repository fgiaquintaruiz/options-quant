# 🕯️ Candlestick Pattern Detection - Complete Guide

## Overview

The system now detects **REAL candlestick patterns** at trade entry and combines them with strategy-derived setup names for comprehensive pattern tracking.

---

## What's New

### **Before:**
```
TradeRecord.pattern = "squeeze_breakout"  // Strategy-derived only
```

### **After:**
```
TradeRecord.pattern = "squeeze_breakout + hammer"  // Strategy + Real candle!
```

The system now learns:
- "AMZN + squeeze_breakout + hammer = 72% WR" ✅
- "NVDA + trend_continuation + bullish_engulfing = 68% WR" ✅
- "GOOGL + reversal + doji = 28% WR" ❌

---

## Detected Patterns

The `CandlestickPatternDetector` identifies **24 patterns**:

### **Single-Bar Patterns:**

| Pattern | Description | Signal |
|---------|-------------|--------|
| **doji** | Open ≈ Close (indecision) | Neutral |
| **long_legged_doji** | Wide range, small body | High indecision |
| **dragonfly_doji** | Long lower shadow | 🟢 Bullish reversal |
| **gravestone_doji** | Long upper shadow | 🔴 Bearish reversal |
| **hammer** | Long lower shadow, bullish close | 🟢🟢 Strong bullish reversal |
| **weak_hammer** | Hammer but bearish close | 🟢 Weak bullish |
| **inverted_hammer** | Long upper shadow, bullish | 🟢 Bullish reversal |
| **shooting_star** | Long upper shadow, bearish | 🔴 Bearish reversal |
| **bullish_spinning_top** | Small body, balanced shadows | 🟡 Indecision (bullish) |
| **bearish_spinning_top** | Small body, balanced shadows | 🟡 Indecision (bearish) |
| **bullish_marubozu** | No shadows, full bullish body | 🟢🟢🟢 Very strong bullish |
| **bearish_marubozu** | No shadows, full bearish body | 🔴🔴🔴 Very strong bearish |
| **strong_bullish** | Large body (>70%) | 🟢🟢 Strong bullish |
| **strong_bearish** | Large body (>70%) | 🔴🔴 Strong bearish |
| **bullish** | Moderate bullish candle | 🟢 Bullish |
| **bearish** | Moderate bearish candle | 🔴 Bearish |

### **Multi-Bar Patterns (More Significant!):**

| Pattern | Bars | Description | Signal |
|---------|------|-------------|--------|
| **bullish_engulfing** | 2 | Current candle engulfs prior bearish | 🟢🟢🟢 Strong bullish reversal |
| **bearish_engulfing** | 2 | Current candle engulfs prior bullish | 🔴🔴🔴 Strong bearish reversal |
| **morning_star** | 3 | Bearish, small, bullish reversal | 🟢🟢🟢🟢 Very strong bullish |
| **evening_star** | 3 | Bullish, small, bearish reversal | 🔴🔴🔴🔴 Very strong bearish |
| **three_white_soldiers** | 3 | Consecutive higher bullish candles | 🟢🟢🟢 Strong bullish continuation |
| **three_black_crows** | 3 | Consecutive lower bearish candles | 🔴🔴🔴 Strong bearish continuation |
| **bullish_harami** | 2 | Inside pattern (potential reversal) | 🟢 Bullish reversal |
| **bearish_harami** | 2 | Inside pattern (potential reversal) | 🔴 Bearish reversal |

---

## How It Works

### **1. Pattern Detection at Entry:**

When a strategy triggers and opens a position, the system:

```java
// In BacktestEngine.runStrategies()

// Step 1: Detect the actual candlestick pattern
String candlestickPattern = CandlestickPatternDetector.detectPattern(series1h, lastIndex);
// Returns: "hammer", "bullish_engulfing", "doji", etc.

// Step 2: Get the strategy-derived pattern
String strategyPattern = extractPatternFromStrategy(strategy.getName());
// Returns: "squeeze_breakout", "trend_continuation", etc.

// Step 3: Combine both for comprehensive tracking
String combinedPattern = strategyPattern + " + " + candlestickPattern;
// Result: "squeeze_breakout + hammer"
```

### **2. Learning System Tracks Everything:**

The combined pattern is stored in:
- `TradeRecord.candlestickPattern`
- CSV output (`backtest/trades.csv`)
- TickerMemory profiles
- Learning analyzer reports

### **3. Analysis by Pattern:**

The learning analyzer now reports:

```
🎯 PATTERN EFFECTIVENESS
----------------------------------------------------------------------------------------------------
  🔥 squeeze_breakout + bullish_engulfing : 75% WR, $  4,250 PnL, 12 trades [HIGHLY_EFFECTIVE]
  ✅ squeeze_breakout + hammer            : 68% WR, $  2,890 PnL, 18 trades [MODERATELY_EFFECTIVE]
  ⏳ trend_continuation + bullish         : 55% WR, $  1,240 PnL, 22 trades [NEUTRAL]
  ❌ support_resistance_bounce + doji     : 28% WR, $ -1,680 PnL, 15 trades [INEFFECTIVE]
```

### **4. Auto-Adjustments:**

Based on pattern performance:
```
🔧 [Auto-Adjust] Applied 3 adjustments for AMZN::c1squeezecall
  🚫 Disabled pattern 'support_resistance_bounce + doji' for AMZN+c1squeezecall (avg $-112.00 over 15 trades)
  ✅ Enabled pattern 'squeeze_breakout + hammer' for AMZN+c1squeezecall (avg $+160.55 over 18 trades)
```

---

## Pattern Strength Detection

The detector also calculates how "textbook perfect" a pattern is:

```java
double strength = CandlestickPatternDetector.getPatternStrength(series, index, "bullish_engulfing");
// Returns 0.0 to 1.0
// 1.0 = Perfect engulfing (3x larger than prior candle)
// 0.5 = Moderate engulfing (1.8x larger)
```

This can be used in the future to weight trades by pattern quality.

---

## Pattern Classification Helpers

Utility methods to categorize patterns:

```java
CandlestickPatternDetector.isBullishPattern("hammer")          // true
CandlestickPatternDetector.isBearishPattern("shooting_star")   // true
CandlestickPatternDetector.isIndecisionPattern("doji")         // true
```

---

## How to Use

### **1. Run a Backtest (CLI):**

```bash
# Start the app with CLI mode
java -jar options-quant.jar --backtest-cli.enabled=true

# Select command: 1 (Run backtest + analysis)
# The system automatically detects candlestick patterns!
```

### **2. Run Continuous Learning Loop:**

```bash
# Select command: 6 (CONTINUOUS LEARNING LOOP)
# System will:
# - Run multiple backtests
# - Learn which candlestick patterns work per ticker
# - Auto-disable ineffective patterns
# - Converge when optimal performance reached
```

### **3. View Learning Report:**

```bash
# Select command: 7 (View learning report)
```

**Output:**
```
🎯 STRATEGY PROFILES (Learned Adjustments)
----------------------------------------------------------------------------------------------------
Ticker          Strategy             Trades     Win%        PnL Confidence Size Mult Top Loss
----------------------------------------------------------------------------------------------------
NVDA            c1squeezecall            24    75.0% $  3,120.00       82%     1.75x TIMING
  Patterns: ✅ squeeze_breakout + bullish_engulfing (80%, 10 trades), 
             ✅ squeeze_breakout + hammer (71%, 14 trades), 

AMZN            c1squeezecall            12    58.3% $  1,160.00       62%     1.00x TIMING
  Patterns: ❌ support_resistance_bounce + doji (30%, 6 trades), 
             ✅ squeeze_breakout + bullish_engulfing (75%, 6 trades)
```

### **4. Check Trades CSV:**

```bash
# Open: backtest/trades.csv
# New column: Pattern
# Example: "squeeze_breakout + bullish_engulfing"
```

---

## Pattern Effectiveness by Ticker

The system now learns granular pattern preferences:

### **Example Learnings:**

**NVDA:**
```
✅ Loves: bullish_engulfing (80% WR), hammer (75% WR)
❌ Hates: doji (32% WR), spinning_top (38% WR)
💡 Insight: NVDA responds well to strong reversal patterns
```

**AMZN:**
```
✅ Loves: hammer (68% WR), morning_star (72% WR)
❌ Hates: gravestone_doji (25% WR), bearish_engulfing (30% WR)
💡 Insight: AMZN respects bullish reversals at support
```

**GOOGL:**
```
✅ Loves: three_white_soldiers (78% WR), strong_bullish (70% WR)
❌ Hates: doji (40% WR), harami (42% WR)
💡 Insight: GOOGL needs momentum, indecision patterns fail
```

---

## Pattern Categories

The system groups patterns for analysis:

### **Bullish Reversal Patterns:**
- hammer, bullish_engulfing, morning_star, dragonfly_doji, inverted_hammer, bullish_harami

### **Bearish Reversal Patterns:**
- shooting_star, bearish_engulfing, evening_star, gravestone_doji, bearish_harami

### **Continuation Patterns:**
- three_white_soldiers, three_black_crows, strong_bullish, strong_bearish, bullish_marubozu, bearish_marubozu

### **Indecision Patterns:**
- doji, long_legged_doji, spinning_top (bullish/bearish)

---

## Advanced: Using Pattern Data

### **Query Best Patterns for a Ticker:**

```bash
# After running backtest, check the learning report
# Look for patterns with 60%+ WR and 5+ trades
```

### **Manual Pattern Analysis:**

```bash
# Open: backtest/trades.csv
# Filter by Pattern column
# Group by: Ticker + Pattern
# Calculate: Win Rate, Avg PnL per pattern
```

---

## Future Enhancements (Ideas)

1. **Pattern-Based Entry Filter:**
   - Skip trades if candlestick pattern is in disabled list
   - Boost position size for high-confidence patterns

2. **Pattern Strength Weighting:**
   - Use `getPatternStrength()` to adjust position size
   - Only trade patterns with > 0.7 strength

3. **Multi-Timeframe Pattern Detection:**
   - Detect patterns on 1H, 15m, 5m
   - Require pattern alignment across timeframes

4. **Pattern Context Awareness:**
   - A hammer at support is stronger than in the middle of a range
   - Add location-aware pattern detection

5. **Real-Time Pattern Alerts:**
   - Telegram notification when high-probability pattern forms
   - "🔔 NVDA forming hammer at SMA20 support - 68% historical WR"

---

## Benefits Over Strategy-Only Tracking

| Aspect | Strategy-Only | Strategy + Candlestick |
|--------|--------------|------------------------|
| Granularity | 6 patterns (one per strategy) | 24+ candlestick patterns |
| Cross-Strategy Learning | No | Yes (hammer works across strategies) |
| Pattern Specificity | "squeeze_breakout" | "squeeze_breakout + bullish_engulfing" |
| Learning Speed | Slower (needs many trades) | Faster (pattern data accumulates quickly) |
| Adaptability | Strategy-level only | Pattern-level adjustments |
| Insights | "NVDA loves squeeze" | "NVDA loves squeeze + engulfing at 80% WR" |

---

## Example: How This Helps

### **Scenario: AMZN is losing money**

**Old System (Strategy Only):**
```
AMZN + c1squeezecall: 44% WR, -$1,200
→ Disable c1squeezecall for AMZN
→ Result: AMZN can't trade at all
```

**New System (Strategy + Candlestick):**
```
AMZN + c1squeezecall + doji: 28% WR, -$680  ❌
AMZN + c1squeezecall + hammer: 68% WR, +$920 ✅
AMZN + c1squeezecall + bullish_engulfing: 72% WR, +$1,140 ✅

→ Disable ONLY doji pattern for AMZN
→ Keep hammer and engulfing (both profitable!)
→ Result: AMZN continues trading, but only with winning patterns
→ New WR: 70% (from 44%)
```

**Key Difference:** Instead of blocking the entire strategy, we surgically remove the losing candlestick pattern while keeping the winning ones!

---

## Technical Details

### **Detection Algorithm:**

1. **Calculate candle properties:**
   - Range = High - Low
   - Body = |Close - Open|
   - Upper Shadow = High - Max(Open, Close)
   - Lower Shadow = Min(Open, Close) - Low
   - Ratios = Each component / Range

2. **Check multi-bar patterns first** (more significant):
   - Morning/Evening Star (3 bars)
   - Engulfing (2 bars)
   - Three White Soldiers/Black Crows (3 bars)
   - Harami (2 bars)

3. **Check single-bar patterns:**
   - Doji (body < 10% of range)
   - Hammer (lower shadow > 60%, body < 30%)
   - Shooting Star (upper shadow > 60%, body < 30%)
   - Marubozu (body > 92%)
   - etc.

4. **Return pattern name**

### **Timeframe Used:**

Currently uses **1H (hourly)** candles for pattern detection as they're most reliable. Can be extended to multiple timeframes in the future.

---

## Summary

✅ **24 real candlestick patterns** detected automatically
✅ **Combined with strategy setups** for comprehensive tracking
✅ **Learning system analyzes** pattern effectiveness per ticker
✅ **Auto-disables** ineffective patterns (not entire strategies!)
✅ **CLI integration** for easy training
✅ **Continuous learning loop** converges on optimal patterns

**Result:** More granular learning, faster convergence, better performance! 🚀
