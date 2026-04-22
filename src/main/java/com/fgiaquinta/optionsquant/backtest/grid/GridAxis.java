package com.fgiaquinta.optionsquant.backtest.grid;

import java.util.List;

/**
 * One search axis: parameter name and discrete values (e.g. TP delta steps).
 */
public record GridAxis(String name, List<Double> values) {
}
