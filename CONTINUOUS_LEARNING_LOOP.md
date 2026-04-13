# 🔄 Continuous Learning Loop - User Guide

## Overview

The **Continuous Learning Loop** is your **TRAINING MODE** - it runs backtests automatically, learns from each one, and keeps going until it reaches optimal performance. Think of it as your bot practicing in a simulator before going live!

---

## 🎯 How It Works

### **The Loop:**
```
┌─────────────────────────────────────────────────────┐
│ 1. Run Backtest                                     │
│    ↓                                                │
│ 2. Record all trades with patterns & context        │
│    ↓                                                │
│ 3. Analyze: pattern effectiveness, loss categories  │
│    ↓                                                │
│ 4. Auto-apply learnings:                            │
│    - Disable ineffective patterns                   │
│    - Adjust ATR multipliers                         │
│    - Add time/VIX filters                           │
│    - Adjust position sizes                          │
│    ↓                                                │
│ 5. Check convergence:                               │
│    - Win rate stable for 3 iterations? → STOP       │
│    - Max iterations reached? → STOP                 │
│    - Still improving? → Repeat from step 1          │
└─────────────────────────────────────────────────────┘
```

### **When Does It Stop?**

The loop stops when **EITHER**:
1. ✅ **Convergence detected**: Win rate improved less than 2% for 3 consecutive iterations
2. 🔄 **Max iterations reached**: Hit the limit (default: 20 iterations)
3. 🛑 **Manual stop**: You called `/api/backtest/learn/stop`

---

## 🚀 How to Use

### **Method 1: API Calls (Recommended)**

#### **Start the Learning Loop:**
```bash
curl -X POST "http://localhost:8080/api/backtest/learn?from=2025-01-01&to=2026-04-01&tickers=AMZN,NVDA,GOOGL&maxIterations=20"
```

**Parameters:**
- `from`: Start date (YYYY-MM-DD)
- `to`: End date (YYYY-MM-DD)
- `tickers`: Comma-separated list
- `initialCapital`: Starting cash (default: 50000)
- `riskPct`: Risk per trade (default: 0.02 = 2%)
- `maxIterations`: Max loop count (default: 20)
- `convergenceThreshold`: Stop threshold (default: 0.02 = 2%)

#### **Check Status:**
```bash
curl http://localhost:8080/api/backtest/learn/status
```

#### **Stop the Loop:**
```bash
curl -X POST "http://localhost:8080/api/backtest/learn/stop"
```

#### **View Learning Report:**
```bash
curl http://localhost:8080/api/backtest/learning-report
```

---

### **Method 2: Run Configuration (IntelliJ IDEA)**

I'll create a new run configuration for you. Here's how to set it up:

1. **Go to**: Run → Edit Configurations
2. **Click**: + → Spring Boot
3. **Name**: `Continuous Learning Loop`
4. **Main class**: `com.fgiaquinta.optionsquant.OptionsQuantApplication`
5. **VM options**: (leave blank)
6. **Program arguments**: `--learning-loop.enabled=true --learning-loop.tickers=AMZN,NVDA,GOOGL --learning-loop.max-iterations=20`
7. **Working directory**: `$MODULE_WORKING_DIR$`

Then just click **Run** and watch it go!

---

### **Method 3: Create a Test/Runner Class**

For easier testing, you can create a simple runner:

```java
// src/main/java/com/fgiaquinta/optionsquant/service/LearningLoopRunner.java
@Component
@Profile("learning-loop")
public class LearningLoopRunner implements CommandLineRunner {
    
    private final ContinuousLearningLoop loop;
    
    public LearningLoopRunner(ContinuousLearningLoop loop) {
        this.loop = loop;
    }
    
    @Override
    public void run(String... args) {
        loop.startLoop(
            List.of("AMZN", "NVDA", "GOOGL"),
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2026, 4, 1),
            50000, 0.02, 20, 0.02
        );
    }
}
```

Then create a run configuration with:
- **Program arguments**: `--spring.profiles.active=learning-loop`

---

## 📊 How to Know When You've Reached the Best Limit

### **Signs You've Converged:**

#### **1. Convergence Detection (Automatic)**
```
🎯 [Convergence] Reached optimal performance after 12 iterations!
   Final Win Rate: 68.5%
   Final PnL: $8,450.00
```

#### **2. Watch the Logs for These Patterns:**

**Still Learning (Good!):**
```
🔄 ITERATION 5 of 20
✅ Iteration 5 complete: 45 trades, 58.3% WR, $2,340.00 PnL
🔧 [Auto-Adjust] Applied 4 adjustments for AMZN::c1squeezecall
```

**Converging (Almost There):**
```
⏸️ [Convergence] No significant improvement (WR: 1.2%, PnL: $145.00) - count: 1/3
⏸️ [Convergence] No significant improvement (WR: 0.8%, PnL: $98.00) - count: 2/3
🎯 [Convergence] Reached optimal performance after 12 iterations!
```

**Plateaued (Stop and Review):**
```
Iteration 8: 62.1% WR, $5,230 PnL
Iteration 9: 62.3% WR, $5,280 PnL
Iteration 10: 62.2% WR, $5,260 PnL
→ Δ Win Rate from first iteration: +12.1%
→ System has learned what it can from this data
```

---

### **Key Metrics to Watch:**

#### **Win Rate Progression:**
```
Iter 1: 50.0% WR (baseline)
Iter 2: 54.2% WR (+4.2%) ← Still learning
Iter 3: 58.7% WR (+4.5%) ← Still learning
Iter 4: 61.3% WR (+2.6%) ← Slowing down
Iter 5: 62.1% WR (+0.8%) ← Converging
Iter 6: 62.3% WR (+0.2%) ← Converged!
Iter 7: 62.2% WR (-0.1%) ← Converged!
Iter 8: 62.4% WR (+0.2%) ← Converged! → STOP
```

#### **PnL Progression:**
```
Iter 1: $1,200
Iter 2: $2,800  ← Patterns disabled, stops adjusted
Iter 3: $3,900  ← Time filters added
Iter 4: $4,500  ← Position sizes optimized
Iter 5: $4,800  ← Marginal improvement
Iter 6: $4,850  ← Converged
```

---

## 🎓 What to Do After Convergence

### **1. Review the Learning Report:**
```bash
GET /api/backtest/learning-report
```

Look for:
- ✅ Which tickers have high confidence (70%+)?
- ❌ Which patterns were disabled?
- 📝 What are the top loss categories?
- 🔧 What auto-adjustments were applied?

### **2. Get AI Analysis:**
```bash
POST /api/backtest/ai-analysis
```

Ollama will tell you:
- Critical issues remaining
- What else you can try
- Whether you're ready for live trading

### **3. Validate with Fresh Data:**
```bash
# Test on a DIFFERENT date range to ensure it's not overfit
POST /api/backtest/learn?from=2026-04-01&to=2026-10-01&tickers=AMZN,NVDA,GOOGL&maxIterations=5
```

If performance holds up on new data → **You're ready for paper trading!**

### **4. Decision Point:**

| Metric | Ready for Live | Needs More Work |
|--------|---------------|-----------------|
| Win Rate | > 60% | < 55% |
| Profit Factor | > 1.5 | < 1.2 |
| Max Drawdown | < 15% | > 25% |
| Confidence Scores | 70%+ on top tickers | < 50% |
| Convergence | Reached in < 15 iterations | Hit max iterations |

---

## 🔧 Troubleshooting

### **"No trades in iteration" Error:**
```
⚠️ [Learning Loop] No trades in iteration 3, stopping
```

**Why:** Strategy filters are too strict after learning disabled patterns.

**Fix:** 
- Reset memory: `POST /api/memory/reset-all`
- Reduce maxIterations and try again
- Check if all patterns got disabled (learning report)

### **Loop Runs Forever (No Convergence):**
```
⏸️ [Convergence] No significant improvement - count: 1/3
✅ Iteration 8 complete...
⏸️ [Convergence] No significant improvement - count: 1/3  ← Reset!
```

**Why:** Volatile performance keeps fluctuating.

**Fix:**
- Increase `convergenceThreshold` to 0.05 (5%)
- Decrease `maxIterations` to 15

### **Performance Gets Worse:**
```
Iter 1: 58% WR, $3,200 PnL
Iter 2: 52% WR, $1,800 PnL  ← Got worse!
Iter 3: 48% WR, $900 PnL   ← Even worse!
```

**Why:** Overfitting - system is tweaking too aggressively.

**Fix:**
- Reset memory and restart with fewer iterations (10 max)
- Manually review what was disabled (might have disabled good patterns)
- Check if ATR multipliers became too wide

---

## 📝 Example: Full Training Session

### **Start:**
```bash
curl -X POST "http://localhost:8080/api/backtest/learn?from=2025-01-01&to=2026-04-01&tickers=AMZN,NVDA,GOOGL&maxIterations=20"
```

### **Watch Logs (Real-time):**
```
🚀 [Learning Loop] Starting continuous training with 3 tickers, max 20 iterations
═══════════════════════════════════════════════════════════════════════════════
🔄 ITERATION 1 of 20
═══════════════════════════════════════════════════════════════════════════════
📊 Running backtest...
<<< Backtest complete: equity=54230.50, elapsed=3245ms
✅ Iteration 1 complete: 38 trades, 52.6% WR, $4,230.50 PnL, PF=1.45
🧠 [Learning] Analyzing results...
🔧 [Auto-Adjust] Applied 5 adjustments for AMZN::c1squeezecall

═══════════════════════════════════════════════════════════════════════════════
🔄 ITERATION 2 of 20
═══════════════════════════════════════════════════════════════════════════════
...

═══════════════════════════════════════════════════════════════════════════════
🔄 ITERATION 8 of 20
═══════════════════════════════════════════════════════════════════════════════
✅ Iteration 8 complete: 52 trades, 63.5% WR, $5,840.00 PnL, PF=1.92
⏸️ [Convergence] No significant improvement (WR: 1.2%, PnL: $120.00) - count: 1/3

═══════════════════════════════════════════════════════════════════════════════
🔄 ITERATION 9 of 20
═══════════════════════════════════════════════════════════════════════════════
✅ Iteration 9 complete: 54 trades, 63.8% WR, $5,920.00 PnL, PF=1.95
⏸️ [Convergence] No significant improvement (WR: 0.3%, PnL: $80.00) - count: 2/3

═══════════════════════════════════════════════════════════════════════════════
🔄 ITERATION 10 of 20
═══════════════════════════════════════════════════════════════════════════════
✅ Iteration 10 complete: 53 trades, 64.1% WR, $5,960.00 PnL, PF=1.97
⏸️ [Convergence] No significant improvement (WR: 0.3%, PnL: $40.00) - count: 3/3
🎯 [Convergence] Reached optimal performance after 10 iterations!

═══════════════════════════════════════════════════════════════════════════════
🎓 [Learning Loop] TRAINING COMPLETE!
═══════════════════════════════════════════════════════════════════════════════
📊 RESULTS:
   Iterations: 10
   Reason: No improvement for 3 consecutive iterations
   Elapsed: 42350 ms (0.7 minutes)

📈 IMPROVEMENT:
   First: 38 trades, 52.6% WR, $4,230.50 PnL
   Last:  53 trades, 64.1% WR, $5,960.00 PnL
   Δ Win Rate: +11.5%
   Δ PnL: $+1,729.50
```

### **Check Final State:**
```bash
curl http://localhost:8080/api/backtest/learning-report
```

**Output:**
```
🧠 Ticker Memory & Learning Report
====================================================================================================

📊 AGGREGATE TICKER STATS
----------------------------------------------------------------------------------------------------
Ticker   Trades     Win%        PnL         PF   Streak   Multiplier Last Strategy
----------------------------------------------------------------------------------------------------
NVDA         24    75.0% $  3,120.00      3.25    12         1.75x c1squeezecall
GOOGL        18    66.7% $  1,680.00      2.45     6         1.50x c2trendcall
AMZN         12    58.3% $  1,160.00      1.68     3         1.00x c1squeezecall

🎯 STRATEGY PROFILES (Learned Adjustments)
----------------------------------------------------------------------------------------------------
Ticker          Strategy             Trades     Win%        PnL Confidence Size Mult Top Loss
----------------------------------------------------------------------------------------------------
NVDA            c1squeezecall            24    75.0% $  3,120.00       82%     1.75x TIMING
  Patterns: ✅ squeeze_breakout (75%, 24 trades), 
GOOGL           c2trendcall              18    66.7% $  1,680.00       72%     1.50x MOMENTUM
  Patterns: ✅ trend_continuation (67%, 18 trades), 
AMZN            c1squeezecall            12    58.3% $  1,160.00       62%     1.00x TIMING
  Patterns: ❌ support_resistance_bounce (33%, 6 trades), ✅ squeeze_breakout (71%, 6 trades)

📝 LOSS CATEGORIZATION SUMMARY
----------------------------------------------------------------------------------------------------
  TIMING              : 8 losses (44.4%)
  MOMENTUM            : 5 losses (27.8%)
  VOLATILITY          : 3 losses (16.7%)
  STRATEGY_MISMATCH   : 2 losses (11.1%)
```

### **Get AI Recommendations:**
```bash
curl -X POST http://localhost:8080/api/backtest/ai-analysis
```

**AI says:**
```
🔍 CRITICAL ISSUES:
1. TIMING losses are 44% of all losses — focus on entry time optimization
2. AMZN still has 58% WR — acceptable but could improve with better time filters
3. VOLATILITY losses suggest stops might still be too tight on some tickers

🎯 PATTERN OPTIMIZATION:
- NVDA: squeeze_breakout is performing at 75% — INCREASE position size to 2.0x
- AMZN: Consider disabling morning trades entirely (before 10 AM)
- All tickers: trend_continuation works well — prioritize over reversal patterns

⚙️ PARAMETER TUNING:
- AMZN: Add time filter 10:00-15:00 (loses 70% before 10 AM)
- NVDA: Current 1.75x size is good — test 2.0x in next iteration
- GOOGL: Widen ATR stop from 2.2x to 2.5x (too many momentum stops)

🛡️ RISK MANAGEMENT:
- All tickers above 58% WR — GOOD for paper trading
- Profit factors > 1.5 — READY for small live tests
- Max drawdown 12% — WITHIN acceptable range

📈 NEXT STEPS:
1. ✅ System has converged — performance is stable
2. Test on different date range to validate (avoid overfitting)
3. If validation passes → Start small live trading ($50k account, 1% risk)
4. Continue monitoring and learning in live mode
```

---

## 🎯 When to Stop and Go Live

### **Green Lights (Ready!):**
- ✅ Converged in < 15 iterations
- ✅ Win rate > 60% across all tickers
- ✅ Profit factor > 1.5
- ✅ Max drawdown < 15%
- ✅ AI analysis says "ready for live tests"
- ✅ Validated on different date range

### **Red Flags (Keep Training!):**
- ❌ Hit max iterations without converging
- ❌ Win rate < 55%
- ❌ Profit factor < 1.2
- ❌ Performance declining in later iterations
- ❌ All patterns disabled for some tickers
- ❌ AI analysis suggests major issues

---

## 💡 Pro Tips

1. **Start Small**: Use 3-5 tickers first, not your full list
2. **Use Representative Data**: Pick date ranges that include different market conditions
3. **Watch the First 5 Iterations**: That's where most learning happens
4. **Don't Over-Optimize**: If it converges at 62% WR, don't keep chasing 70%
5. **Validate Always**: After convergence, test on a NEW date range to ensure it's not overfit
6. **Keep Learning in Live Mode**: The system continues learning even when trading live!

---

## 🚀 Quick Start Commands

```bash
# 1. Reset memory (clean slate)
curl -X POST http://localhost:8080/api/memory/reset-all

# 2. Start learning loop
curl -X POST "http://localhost:8080/api/backtest/learn?from=2025-01-01&to=2026-04-01&tickers=AMZN,NVDA,GOOGL&maxIterations=20"

# 3. Monitor status
curl http://localhost:8080/api/backtest/learn/status

# 4. Wait for convergence...

# 5. View results
curl http://localhost:8080/api/backtest/learning-report

# 6. Get AI analysis
curl -X POST http://localhost:8080/api/backtest/ai-analysis

# 7. Validate on fresh data
curl -X POST "http://localhost:8080/api/backtest/learn?from=2026-04-01&to=2026-10-01&tickers=AMZN,NVDA,GOOGL&maxIterations=5"

# 8. If validation passes → Ready for paper trading! 🎉
```

---

## 📞 Support

All learning activity is logged to your application logs:
```
🧠 [Memory] NVDA update: 24 trades, 75.0% WR, PnL $3,120.00, confidence 82%
📝 [Memory] AMZN loss categorized as: TIMING (strategy: c1squeezecall, PnL: $-180.00)
🔧 [Auto-Adjust] Applied 5 adjustments for AMZN::c1squeezecall
🎯 [Convergence] Reached optimal performance after 10 iterations!
```

Watch these logs to see the system learn in real-time!
