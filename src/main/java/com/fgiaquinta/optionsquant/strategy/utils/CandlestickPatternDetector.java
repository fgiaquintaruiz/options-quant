package com.fgiaquinta.optionsquant.strategy.utils;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

/**
 * Detects real candlestick patterns from OHLCV data.
 * 
 * This provides granular pattern recognition that works ACROSS strategies:
 * - A hammer might be good for BOTH squeeze and trend setups
 * - Doji might indicate reversal potential
 * - Engulfing patterns show momentum shifts
 * 
 * The learning system tracks which patterns work per ticker:
 * "AMZN responds to hammers (68% WR) but not dojis (32% WR)"
 */
public class CandlestickPatternDetector {

    /**
     * Detects the candlestick pattern at the given index.
     * 
     * @param series The bar series
     * @param index The index to analyze
     * @return Pattern name (e.g., "hammer", "bullish_engulfing", "doji")
     */
    public static String detectPattern(BarSeries series, int index) {
        if (series == null || index < 0 || index >= series.getBarCount()) {
            return "unknown";
        }

        Bar current = series.getBar(index);
        double open = current.getOpenPrice().doubleValue();
        double close = current.getClosePrice().doubleValue();
        double high = current.getHighPrice().doubleValue();
        double low = current.getLowPrice().doubleValue();
        
        double range = high - low;
        if (range <= 0) return "doji";  // No movement
        
        double body = Math.abs(close - open);
        double upperShadow = high - Math.max(open, close);
        double lowerShadow = Math.min(open, close) - low;
        double bodyRatio = body / range;
        double upperRatio = upperShadow / range;
        double lowerRatio = lowerShadow / range;
        
        boolean isBullish = close > open;
        
        // Check multi-bar patterns first (more significant)
        if (index >= 2) {
            String multiBarPattern = detectMultiBarPattern(series, index, open, close, high, low, range, body, isBullish);
            if (!"none".equals(multiBarPattern)) {
                return multiBarPattern;
            }
        }
        
        // Check single-bar patterns
        if (index >= 1) {
            Bar prev = series.getBar(index - 1);
            double prevBody = Math.abs(prev.getClosePrice().doubleValue() - prev.getOpenPrice().doubleValue());
            
            // Engulfing patterns
            if (body > prevBody * 1.5 && bodyRatio > 0.6) {
                double prevOpen = prev.getOpenPrice().doubleValue();
                double prevClose = prev.getClosePrice().doubleValue();
                
                if (isBullish && prevClose < prevOpen) {
                    if (open <= prevClose && close >= prevOpen) {
                        return "bullish_engulfing";
                    }
                } else if (!isBullish && prevClose > prevOpen) {
                    if (open >= prevClose && close <= prevOpen) {
                        return "bearish_engulfing";
                    }
                }
            }
        }
        
        // Doji (indecision)
        if (bodyRatio < 0.1) {
            // Long-legged doji
            if (upperRatio > 0.4 && lowerRatio > 0.4) {
                return "long_legged_doji";
            }
            // Dragonfly doji (bullish)
            if (lowerRatio > 0.7 && upperRatio < 0.2) {
                return "dragonfly_doji";
            }
            // Gravestone doji (bearish)
            if (upperRatio > 0.7 && lowerRatio < 0.2) {
                return "gravestone_doji";
            }
            return "doji";
        }
        
        // Hammer (bullish reversal at bottom)
        if (lowerRatio > 0.6 && bodyRatio < 0.3 && upperRatio < 0.2) {
            return isBullish ? "hammer" : "weak_hammer";
        }
        
        // Inverted Hammer
        if (upperRatio > 0.6 && bodyRatio < 0.3 && lowerRatio < 0.2) {
            return isBullish ? "inverted_hammer" : "shooting_star";
        }
        
        // Spinning Top (indecision with small body)
        if (bodyRatio < 0.3 && upperRatio > 0.25 && lowerRatio > 0.25) {
            return isBullish ? "bullish_spinning_top" : "bearish_spinning_top";
        }
        
        // Marubozu (strong momentum, no shadows)
        if (bodyRatio > 0.92) {
            return isBullish ? "bullish_marubozu" : "bearish_marubozu";
        }
        
        // Strong candle (large body)
        if (bodyRatio > 0.7) {
            return isBullish ? "strong_bullish" : "strong_bearish";
        }
        
        // Normal candle
        return isBullish ? "bullish" : "bearish";
    }

    /**
     * Detects multi-bar candlestick patterns.
     */
    private static String detectMultiBarPattern(BarSeries series, int index, 
                                                  double open, double close, double high, double low,
                                                  double range, double body, boolean isBullish) {
        Bar prev1 = series.getBar(index - 1);
        Bar prev2 = series.getBar(index - 2);
        
        double prev1Open = prev1.getOpenPrice().doubleValue();
        double prev1Close = prev1.getClosePrice().doubleValue();
        double prev2Open = prev2.getOpenPrice().doubleValue();
        double prev2Close = prev2.getClosePrice().doubleValue();
        
        boolean prev1Bullish = prev1Close > prev1Open;
        boolean prev2Bullish = prev2Close > prev2Open;
        
        // Morning Star (bullish reversal)
        if (!prev2Bullish && !isBullish(prev1) && isBullish) {
            double prev1Body = Math.abs(prev1Close - prev1Open);
            double prev1Range = prev1.getHighPrice().doubleValue() - prev1.getLowPrice().doubleValue();
            if (prev1Body < prev1Range * 0.3 && body > prev1Body * 2) {
                return "morning_star";
            }
        }
        
        // Evening Star (bearish reversal)
        if (prev2Bullish && !isBullish(prev1) && !isBullish) {
            double prev1Body = Math.abs(prev1Close - prev1Open);
            double prev1Range = prev1.getHighPrice().doubleValue() - prev1.getLowPrice().doubleValue();
            if (prev1Body < prev1Range * 0.3 && body > prev1Body * 2) {
                return "evening_star";
            }
        }
        
        // Three White Soldiers (strong bullish)
        if (isBullish && prev1Bullish && prev2Bullish) {
            if (close > prev1Close && prev1Close > prev2Close) {
                return "three_white_soldiers";
            }
        }
        
        // Three Black Crows (strong bearish)
        if (!isBullish && !prev1Bullish && !prev2Bullish) {
            if (close < prev1Close && prev1Close < prev2Close) {
                return "three_black_crows";
            }
        }
        
        // Harami (inside pattern)
        double prev1Body = Math.abs(prev1Close - prev1Open);
        if (body < prev1Body * 0.5) {
            if (Math.min(open, close) > Math.min(prev1Open, prev1Close) &&
                Math.max(open, close) < Math.max(prev1Open, prev1Close)) {
                return isBullish ? "bullish_harami" : "bearish_harami";
            }
        }
        
        return "none";
    }

    /**
     * Checks if a bar is bullish (close > open).
     */
    private static boolean isBullish(Bar bar) {
        return bar.getClosePrice().doubleValue() > bar.getOpenPrice().doubleValue();
    }

    /**
     * Gets the pattern strength/confidence (0.0 to 1.0).
     * Higher values mean the pattern is more "textbook perfect".
     */
    public static double getPatternStrength(BarSeries series, int index, String pattern) {
        if (series == null || index < 0 || index >= series.getBarCount()) {
            return 0.0;
        }

        Bar current = series.getBar(index);
        double open = current.getOpenPrice().doubleValue();
        double close = current.getClosePrice().doubleValue();
        double high = current.getHighPrice().doubleValue();
        double low = current.getLowPrice().doubleValue();
        double range = high - low;
        
        if (range <= 0) return 0.5;
        
        double body = Math.abs(close - open);
        double bodyRatio = body / range;
        
        // Stronger patterns have higher body ratios
        if (pattern.contains("marubozu")) {
            return Math.min(1.0, bodyRatio / 0.95);
        }
        if (pattern.contains("strong")) {
            return Math.min(1.0, bodyRatio / 0.75);
        }
        if (pattern.contains("engulfing")) {
            if (index < 1) return 0.5;
            Bar prev = series.getBar(index - 1);
            double prevBody = Math.abs(prev.getClosePrice().doubleValue() - prev.getOpenPrice().doubleValue());
            double engulfRatio = body / Math.max(prevBody, 0.01);
            return Math.min(1.0, engulfRatio / 3.0);  // 3x larger = perfect
        }
        if (pattern.contains("hammer") || pattern.contains("shooting_star")) {
            double lowerShadow = Math.min(open, close) - low;
            double lowerRatio = lowerShadow / range;
            return Math.min(1.0, lowerRatio / 0.65);
        }
        if (pattern.contains("doji")) {
            return Math.min(1.0, (1.0 - bodyRatio) / 0.95);
        }
        
        return 0.5;  // Default moderate strength
    }

    /**
     * Returns a human-readable description of the pattern.
     */
    public static String getPatternDescription(String pattern) {
        return switch (pattern) {
            case "doji" -> "Indecision - open ≈ close";
            case "long_legged_doji" -> "High indecision with wide range";
            case "dragonfly_doji" -> "Bullish reversal - long lower shadow";
            case "gravestone_doji" -> "Bearish reversal - long upper shadow";
            case "hammer" -> "Strong bullish reversal";
            case "weak_hammer" -> "Weak bullish reversal";
            case "inverted_hammer" -> "Bullish reversal - long upper shadow";
            case "shooting_star" -> "Bearish reversal - long upper shadow";
            case "bullish_engulfing" -> "Strong bullish reversal - engulfs prior";
            case "bearish_engulfing" -> "Strong bearish reversal - engulfs prior";
            case "morning_star" -> "3-bar bullish reversal";
            case "evening_star" -> "3-bar bearish reversal";
            case "three_white_soldiers" -> "Strong bullish continuation";
            case "three_black_crows" -> "Strong bearish continuation";
            case "bullish_harami" -> "Inside pattern - potential bullish reversal";
            case "bearish_harami" -> "Inside pattern - potential bearish reversal";
            case "bullish_spinning_top" -> "Indecision - small bullish body";
            case "bearish_spinning_top" -> "Indecision - small bearish body";
            case "bullish_marubozu" -> "Very strong bullish momentum";
            case "bearish_marubozu" -> "Very strong bearish momentum";
            case "strong_bullish" -> "Strong bullish candle";
            case "strong_bearish" -> "Strong bearish candle";
            case "bullish" -> "Moderate bullish";
            case "bearish" -> "Moderate bearish";
            default -> "Normal candle";
        };
    }

    /**
     * Checks if a pattern is considered bullish.
     */
    public static boolean isBullishPattern(String pattern) {
        return pattern.contains("bullish") || 
               pattern.contains("hammer") || 
               pattern.contains("morning_star") ||
               pattern.contains("three_white_soldiers") ||
               pattern.contains("dragonfly");
    }

    /**
     * Checks if a pattern is considered bearish.
     */
    public static boolean isBearishPattern(String pattern) {
        return pattern.contains("bearish") || 
               pattern.contains("shooting_star") || 
               pattern.contains("evening_star") ||
               pattern.contains("three_black_crows") ||
               pattern.contains("gravestone");
    }

    /**
     * Checks if a pattern indicates indecision.
     */
    public static boolean isIndecisionPattern(String pattern) {
        return pattern.contains("doji") || pattern.contains("spinning_top") || pattern.contains("harami");
    }
}
