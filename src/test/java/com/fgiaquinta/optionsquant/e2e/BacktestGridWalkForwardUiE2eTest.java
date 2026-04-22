package com.fgiaquinta.optionsquant.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI smoke for walk-forward controls inside the Info modal (requires at least one trade row with Info).
 */
@Tag("e2e")
@DisplayName("Backtest walk-forward UI")
class BacktestGridWalkForwardUiE2eTest extends BasePlaywrightTest {

    private static final String BT_PAGE = "/backtest";

    private Locator backtestRoot() {
        return page.locator("[data-testid=backtest-dashboard]");
    }

    @Test
    @DisplayName("Info modal exposes walk-forward checkbox when trade rows exist")
    void walkForwardCheckboxInInfoModal() {
        page.navigate(BASE_URL + BT_PAGE);
        page.waitForLoadState();
        backtestRoot().waitFor();

        Locator infoButtons = page.getByTestId("bt-trade-row-info");
        int n = infoButtons.count();
        Assumptions.assumeTrue(n > 0,
                "Sin filas en el trade log no se puede abrir el modal Info; omitiendo UI walk-forward.");

        infoButtons.first().click();
        Locator modal = page.getByRole(AriaRole.DIALOG);
        modal.waitFor();

        assertTrue(modal.getByText(Pattern.compile("Walk-forward")).first().isVisible(),
                "El modal Info debería mostrar la opción walk-forward");
    }
}
