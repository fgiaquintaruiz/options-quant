package com.fgiaquinta.optionsquant.service;

/**
 * Emoji utility with text fallback for consoles that don't support UTF-8.
 *
 * Usage:
 *   Emoji.get("🚀", "[START]")
 *   Emoji.get("✅", "[OK]")
 *   Emoji.get("❌", "[ERROR]")
 *
 * Enable text mode by setting:
 *   -Dno.emoji=true
 *   or
 *   Environment variable: NO_EMOJI=true
 */
public class Emoji {

    private static final boolean NO_EMOJI =
            "true".equalsIgnoreCase(System.getProperty("no.emoji")) ||
            "true".equalsIgnoreCase(System.getenv("NO_EMOJI"));

    /**
     * Returns the emoji if supported, otherwise returns the text fallback.
     */
    public static String get(String emoji, String textFallback) {
        return NO_EMOJI ? textFallback : emoji;
    }

    // ===== Common Emoji Mappings =====

    public static String ROCKET()       { return get("🚀", "[START]"); }
    public static String CHART()        { return get("📊", "[DATA]"); }
    public static String BRAIN()        { return get("🧠", "[LEARN]"); }
    public static String WRITING()      { return get("📝", "[CONFIG]"); }
    public static String INFO()         { return get("ℹ️",  "[INFO]"); }
    public static String REFRESH()      { return get("🔄", "[RUNNING]"); }
    public static String SEARCH()       { return get("🔍", "[SCAN]"); }
    public static String CHECK()        { return get("✅", "[OK]"); }
    public static String CROSS()        { return get("❌", "[ERROR]"); }
    public static String LIGHTBULB()    { return get("💡", "[TIP]"); }
    public static String TREND_UP()     { return get("📈", "[UP]"); }
    public static String TROPHY()       { return get("🏆", "[WINNER]"); }
    public static String FOLDER()       { return get("📂", "[FILES]"); }
    public static String WARNING()      { return get("⚠️",  "[WARN]"); }
    public static String SIREN()        { return get("🚨", "[CRITICAL]"); }
    public static String WRENCH()       { return get("🔧", "[TOOLS]"); }
    public static String WAVE()         { return get("👋", "[BYE]"); }
    public static String FIRE()         { return get("🔥", "[HOT]"); }
    public static String EYES()         { return get("👀", "[VIEW]"); }
    public static String DOWNLOAD()     { return get("📥", "[DOWNLOAD]"); }
    public static String SAVE()         { return get("💾", "[SAVE]"); }
    public static String GLOBE()        { return get("🌐", "[MACRO]"); }
    public static String STOP()         { return get("🛑", "[BLOCKED]"); }
    public static String CLOCK()        { return get("⏰", "[TIME]"); }
    public static String RULER()        { return get("📏", "[MEASURE]"); }
    public static String TARGET()       { return get("🎯", "[TARGET]"); }
    public static String BELL()         { return get("🔔", "[ALERT]"); }
    public static String CLIPBOARD()    { return get("📋", "[REPORT]"); }
    public static String ROBOT()        { return get("🤖", "[BOT]"); }
    public static String GRADUATION()   { return get("🎓", "[TRAINED]"); }

    /**
     * Returns true if emoji mode is disabled.
     */
    public static boolean isNoEmoji() {
        return NO_EMOJI;
    }
}
