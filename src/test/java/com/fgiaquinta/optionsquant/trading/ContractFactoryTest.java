package com.fgiaquinta.optionsquant.trading;

import com.ib.client.Contract;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractFactoryTest {

    @Test
    void testCreateStockContractStandard() {
        Contract contract = ContractFactory.createStockContract("AAPL");
        assertEquals("AAPL", contract.symbol(), "Symbol mismatch");
        assertEquals("STK", contract.getSecType(), "SecType mismatch");
        assertEquals("ISLAND", contract.primaryExch(), "PrimaryExch mismatch");
    }

    @Test
    void testCreateStockContractSpecialNYSE() {
        Contract contract = ContractFactory.createStockContract("SQ");
        assertEquals("SQ", contract.symbol());
        assertEquals("NYSE", contract.primaryExch());
    }

    @Test
    void testCreateStockContractBrkB() {
        Contract contract = ContractFactory.createStockContract("BRK.B");
        // We want this to be "BRK B" but it currently might be "BRKB"
        assertEquals("BRK B", contract.symbol());
        assertEquals("NYSE", contract.primaryExch());
    }

    @Test
    void testCreateOptionContract() {
        Contract contract = ContractFactory.createOptionContract("NVDA", "20260424", 100.0, "CALL", "2NVDA");
        assertEquals("NVDA", contract.symbol());
        assertEquals("OPT", contract.getSecType());
        assertEquals("20260424", contract.lastTradeDateOrContractMonth());
        assertEquals(100.0, contract.strike());
        assertEquals("C", contract.getRight());
        assertEquals("2NVDA", contract.tradingClass());
    }
}
