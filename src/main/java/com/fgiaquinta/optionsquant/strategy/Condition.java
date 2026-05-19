package com.fgiaquinta.optionsquant.strategy;

public interface Condition {
    boolean test();
    String describe();
}
