package com.fgiaquinta.optionsquant.utils;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogManager {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void initialize() {
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(originalOut, true, StandardCharsets.UTF_8) {
            private boolean isNewLine = true;

            @Override
            public void print(String s) {
                if (s == null) s = "null";
                if (isNewLine && !s.isBlank() && !s.startsWith("[")) {
                    super.print("[" + LocalDateTime.now().format(FMT) + "] " + s);
                    isNewLine = false;
                } else {
                    super.print(s);
                }
                if (s.endsWith("\n") || s.endsWith("\r")) {
                    isNewLine = true;
                }
            }

            @Override
            public void println(String s) {
                print((s == null ? "" : s) + "\n");
            }
        });
    }
}