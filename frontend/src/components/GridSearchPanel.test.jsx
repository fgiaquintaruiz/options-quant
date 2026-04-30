import React from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import GridSearchPanel from './GridSearchPanel'

/**
 * Tests for GridSearchPanel — grid search form and walk-forward controls.
 * The component is a pure presentational component driven entirely by the `gridSearch`
 * prop (the return value of useGridSearch hook). All state + actions are mocked here
 * so tests focus on render output and callback wiring.
 */

function makeGridSearch(overrides = {}) {
  return {
    modalLookback: '3',
    setModalLookback: vi.fn(),
    modalGridFrom: '2024-01-01',
    setModalGridFrom: vi.fn(),
    modalGridTo: '2024-04-01',
    setModalGridTo: vi.fn(),
    modalTpAxis: '0.5,1.0,1.5',
    setModalTpAxis: vi.fn(),
    modalSlAxis: '0.5,1.0',
    setModalSlAxis: vi.fn(),
    gridMetric: 'TOTAL_PNL',
    setGridMetric: vi.fn(),
    modalGridMinTrades: '5',
    setModalGridMinTrades: vi.fn(),
    modalGridMaxDdPct: '',
    setModalGridMaxDdPct: vi.fn(),
    modalWalkForward: false,
    setModalWalkForward: vi.fn(),
    modalWfTrainDays: '60',
    setModalWfTrainDays: vi.fn(),
    modalWfTestDays: '30',
    setModalWfTestDays: vi.fn(),
    modalWfStepDays: '',
    setModalWfStepDays: vi.fn(),
    modalGridLoading: false,
    modalGridElapsed: 0,
    modalGridError: null,
    modalGridResult: null,
    retestLoading: false,
    retestError: null,
    retestData: null,
    applyMemoryLoading: false,
    applyMemoryMessage: null,
    runGrid: vi.fn(),
    runRetest: vi.fn(),
    ...overrides,
  }
}

const baseProps = {
  gridSearch: makeGridSearch(),
  ticker: 'AAPL',
  strategyName: 'c1 squeeze',
  startParams: { capital: 100000, risk: 0.01 },
  maxConcurrent: 4,
  running: false,
  onApplyAndStartScan: vi.fn(),
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('GridSearchPanel — initial render', () => {
  it('renders the section title', () => {
    render(<GridSearchPanel {...baseProps} />)
    expect(screen.getByText('Grid search (mismo motor que el panel)')).toBeInTheDocument()
  })

  it('displays ticker in description text', () => {
    render(<GridSearchPanel {...baseProps} />)
    expect(screen.getByText(/AAPL/)).toBeInTheDocument()
  })

  it('renders Run grid search button', () => {
    render(<GridSearchPanel {...baseProps} />)
    const btn = screen.getByTestId('bt-modal-grid-search')
    expect(btn).toBeInTheDocument()
    expect(btn.disabled).toBe(false)
  })

  it('renders Ventana (lookback) select with current value', () => {
    render(<GridSearchPanel {...baseProps} />)
    const select = screen.getByDisplayValue('3 meses')
    expect(select).toBeInTheDocument()
  })

  it('renders Desde date input with current value', () => {
    render(<GridSearchPanel {...baseProps} />)
    const input = screen.getByDisplayValue('2024-01-01')
    expect(input).toBeInTheDocument()
  })

  it('renders Hasta date input with current value', () => {
    render(<GridSearchPanel {...baseProps} />)
    const input = screen.getByDisplayValue('2024-04-01')
    expect(input).toBeInTheDocument()
  })

  it('renders TP Δ input with current value', () => {
    render(<GridSearchPanel {...baseProps} />)
    expect(screen.getByDisplayValue('0.5,1.0,1.5')).toBeInTheDocument()
  })

  it('renders SL Δ input with current value', () => {
    render(<GridSearchPanel {...baseProps} />)
    expect(screen.getByDisplayValue('0.5,1.0')).toBeInTheDocument()
  })

  it('renders Métrica select', () => {
    render(<GridSearchPanel {...baseProps} />)
    expect(screen.getByDisplayValue('P&L total')).toBeInTheDocument()
  })

  it('renders Walk-forward checkbox unchecked by default', () => {
    render(<GridSearchPanel {...baseProps} />)
    const checkbox = screen.getByRole('checkbox')
    expect(checkbox).not.toBeChecked()
  })

  it('does not render walk-forward day inputs when walk-forward is disabled', () => {
    render(<GridSearchPanel {...baseProps} />)
    expect(screen.queryByDisplayValue('60')).toBeNull()
  })

  it('renders capital and risk in description', () => {
    render(<GridSearchPanel {...baseProps} />)
    // Capital 100000 → formatUsd → "100,000"
    expect(screen.getByText(/100,000/)).toBeInTheDocument()
  })
})

describe('GridSearchPanel — button states', () => {
  it('disables Run button when modalGridLoading=true', () => {
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridLoading: true }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByTestId('bt-modal-grid-search').disabled).toBe(true)
  })

  it('disables Run button when running=true', () => {
    render(<GridSearchPanel {...baseProps} running={true} />)
    expect(screen.getByTestId('bt-modal-grid-search').disabled).toBe(true)
  })

  it('disables Run button when ticker is empty', () => {
    render(<GridSearchPanel {...baseProps} ticker="" />)
    expect(screen.getByTestId('bt-modal-grid-search').disabled).toBe(true)
  })

  it('shows loading text with elapsed seconds during grid loading', () => {
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridLoading: true, modalGridElapsed: 7 }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByTestId('bt-modal-grid-search').textContent).toContain('7s')
  })

  it('shows walk-forward loading text when modalWalkForward=true and loading', () => {
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridLoading: true, modalWalkForward: true, modalGridElapsed: 3 }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByTestId('bt-modal-grid-search').textContent).toContain('Walk-forward')
  })
})

describe('GridSearchPanel — callbacks', () => {
  it('calls runGrid when Run button is clicked', async () => {
    const runGrid = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ runGrid }) }
    render(<GridSearchPanel {...props} />)
    await userEvent.click(screen.getByTestId('bt-modal-grid-search'))
    expect(runGrid).toHaveBeenCalledOnce()
  })

  it('calls setModalLookback when Ventana select changes', () => {
    const setModalLookback = vi.fn()
    const setModalGridFrom = vi.fn()
    const setModalGridTo = vi.fn()
    const props = {
      ...baseProps,
      gridSearch: makeGridSearch({ setModalLookback, setModalGridFrom, setModalGridTo }),
    }
    render(<GridSearchPanel {...props} />)
    const select = screen.getByDisplayValue('3 meses')
    fireEvent.change(select, { target: { value: '6' } })
    expect(setModalLookback).toHaveBeenCalledWith('6')
  })

  it('calls setModalGridFrom when Desde date changes', () => {
    const setModalGridFrom = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ setModalGridFrom }) }
    render(<GridSearchPanel {...props} />)
    const dateInput = screen.getByDisplayValue('2024-01-01')
    fireEvent.change(dateInput, { target: { value: '2024-02-01' } })
    expect(setModalGridFrom).toHaveBeenCalledWith('2024-02-01')
  })

  it('calls setModalGridTo when Hasta date changes', () => {
    const setModalGridTo = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ setModalGridTo }) }
    render(<GridSearchPanel {...props} />)
    const dateInput = screen.getByDisplayValue('2024-04-01')
    fireEvent.change(dateInput, { target: { value: '2024-05-01' } })
    expect(setModalGridTo).toHaveBeenCalledWith('2024-05-01')
  })

  it('calls setModalTpAxis when TP Δ input changes', () => {
    const setModalTpAxis = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ setModalTpAxis }) }
    render(<GridSearchPanel {...props} />)
    const input = screen.getByDisplayValue('0.5,1.0,1.5')
    fireEvent.change(input, { target: { value: '0.5,1.0,2.0' } })
    expect(setModalTpAxis).toHaveBeenCalledWith('0.5,1.0,2.0')
  })

  it('calls setModalSlAxis when SL Δ input changes', () => {
    const setModalSlAxis = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ setModalSlAxis }) }
    render(<GridSearchPanel {...props} />)
    const input = screen.getByDisplayValue('0.5,1.0')
    fireEvent.change(input, { target: { value: '0.5,1.5' } })
    expect(setModalSlAxis).toHaveBeenCalledWith('0.5,1.5')
  })

  it('calls setGridMetric when Métrica select changes', () => {
    const setGridMetric = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ setGridMetric }) }
    render(<GridSearchPanel {...props} />)
    const select = screen.getByDisplayValue('P&L total')
    fireEvent.change(select, { target: { value: 'PROFIT_FACTOR' } })
    expect(setGridMetric).toHaveBeenCalledWith('PROFIT_FACTOR')
  })

  it('calls setModalGridMinTrades when Mín. trades input changes', () => {
    const setModalGridMinTrades = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ setModalGridMinTrades }) }
    render(<GridSearchPanel {...props} />)
    const input = screen.getByDisplayValue('5')
    fireEvent.change(input, { target: { value: '10' } })
    expect(setModalGridMinTrades).toHaveBeenCalledWith('10')
  })

  it('calls setModalWalkForward when Walk-forward checkbox is toggled', () => {
    const setModalWalkForward = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ setModalWalkForward }) }
    render(<GridSearchPanel {...props} />)
    const checkbox = screen.getByRole('checkbox')
    fireEvent.click(checkbox)
    expect(setModalWalkForward).toHaveBeenCalled()
  })
})

describe('GridSearchPanel — walk-forward inputs', () => {
  it('shows train/test/step day inputs when walk-forward is enabled', () => {
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalWalkForward: true }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByDisplayValue('60')).toBeInTheDocument()
    expect(screen.getByDisplayValue('30')).toBeInTheDocument()
  })

  it('calls setModalWfTrainDays when Train days input changes', () => {
    const setModalWfTrainDays = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalWalkForward: true, setModalWfTrainDays }) }
    render(<GridSearchPanel {...props} />)
    const input = screen.getByDisplayValue('60')
    fireEvent.change(input, { target: { value: '90' } })
    expect(setModalWfTrainDays).toHaveBeenCalledWith('90')
  })

  it('calls setModalWfTestDays when Test days input changes', () => {
    const setModalWfTestDays = vi.fn()
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalWalkForward: true, setModalWfTestDays }) }
    render(<GridSearchPanel {...props} />)
    const input = screen.getByDisplayValue('30')
    fireEvent.change(input, { target: { value: '20' } })
    expect(setModalWfTestDays).toHaveBeenCalledWith('20')
  })

  it('shows walk-forward button text when walk-forward is enabled', () => {
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalWalkForward: true }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByTestId('bt-modal-grid-search').textContent).toContain('walk-forward')
  })
})

describe('GridSearchPanel — error state', () => {
  it('renders error message when modalGridError is set', () => {
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridError: 'Network timeout' }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('alert').textContent).toBe('Network timeout')
  })

  it('does not render error message when modalGridError is null', () => {
    render(<GridSearchPanel {...baseProps} />)
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('GridSearchPanel — grid result', () => {
  it('renders success result when modalGridResult.success=true', () => {
    const result = { success: true, message: 'Grid completado', rows: [] }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByText('Listo')).toBeInTheDocument()
  })

  it('renders failure result when modalGridResult.success=false', () => {
    const result = { success: false, message: 'Sin resultados', rows: [] }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByText('Falló')).toBeInTheDocument()
  })

  it('renders result message when present', () => {
    const result = { success: true, message: 'Grid completado con 9 celdas', rows: [] }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByText('Grid completado con 9 celdas')).toBeInTheDocument()
  })

  it('does not render result section when modalGridResult is null', () => {
    render(<GridSearchPanel {...baseProps} />)
    expect(screen.queryByText('Listo')).toBeNull()
    expect(screen.queryByText('Falló')).toBeNull()
  })

  it('renders no-winner detail message when optimization has no winner', () => {
    const result = {
      success: true,
      rows: [],
      optimization: { hasWinner: false, detailMessage: 'No cell met min trades filter' },
    }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByRole('status').textContent).toBe('No cell met min trades filter')
  })

  it('renders retest and apply buttons when optimization has a winner', () => {
    const result = {
      success: true,
      rows: [],
      optimization: {
        hasWinner: true,
        bestParameters: { tpMultiplierDelta: 1.0, slMultiplierDelta: 0.5 },
      },
    }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByText(/Validar con retest/i)).toBeInTheDocument()
    expect(screen.getByText(/Guardar mejor celda/i)).toBeInTheDocument()
  })

  it('calls runRetest when Retest button is clicked', async () => {
    const runRetest = vi.fn()
    const result = {
      success: true,
      rows: [],
      optimization: {
        hasWinner: true,
        bestParameters: { tpMultiplierDelta: 1.0, slMultiplierDelta: 0.5 },
      },
    }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result, runRetest }) }
    render(<GridSearchPanel {...props} />)
    await userEvent.click(screen.getByText(/Validar con retest/i))
    expect(runRetest).toHaveBeenCalledOnce()
  })

  it('calls onApplyAndStartScan when Guardar mejor celda button is clicked', async () => {
    const onApplyAndStartScan = vi.fn()
    const result = {
      success: true,
      rows: [],
      optimization: {
        hasWinner: true,
        bestParameters: { tpMultiplierDelta: 1.0, slMultiplierDelta: 0.5 },
      },
    }
    const props = {
      ...baseProps,
      gridSearch: makeGridSearch({ modalGridResult: result }),
      onApplyAndStartScan,
    }
    render(<GridSearchPanel {...props} />)
    await userEvent.click(screen.getByText(/Guardar mejor celda/i))
    expect(onApplyAndStartScan).toHaveBeenCalledOnce()
  })

  it('disables Retest button when retestLoading=true', () => {
    const result = {
      success: true,
      rows: [],
      optimization: {
        hasWinner: true,
        bestParameters: { tpMultiplierDelta: 1.0, slMultiplierDelta: 0.5 },
      },
    }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result, retestLoading: true }) }
    render(<GridSearchPanel {...props} />)
    const retestBtn = screen.getByText('Retest…').closest('button')
    expect(retestBtn.disabled).toBe(true)
  })

  it('shows apply memory loading text when applyMemoryLoading=true', () => {
    const result = {
      success: true,
      rows: [],
      optimization: {
        hasWinner: true,
        bestParameters: { tpMultiplierDelta: 1.0, slMultiplierDelta: 0.5 },
      },
    }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result, applyMemoryLoading: true }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByText('Guardando…')).toBeInTheDocument()
  })

  it('renders applyMemoryMessage success text when present', () => {
    const result = {
      success: true,
      rows: [],
      optimization: {
        hasWinner: true,
        bestParameters: { tpMultiplierDelta: 1.0, slMultiplierDelta: 0.5 },
      },
    }
    const props = {
      ...baseProps,
      gridSearch: makeGridSearch({
        modalGridResult: result,
        applyMemoryMessage: { ok: true, text: 'Memoria guardada' },
      }),
    }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByText('Memoria guardada')).toBeInTheDocument()
  })
})

describe('GridSearchPanel — grid rows table', () => {
  it('renders grid rows table when rows are present', () => {
    const result = {
      success: true,
      rows: [
        { cellIndex: 0, parameters: { tpMultiplierDelta: 1.0, slMultiplierDelta: 0.5 }, totalPnl: 500, totalTrades: 10 },
        { cellIndex: 1, parameters: { tpMultiplierDelta: 1.5, slMultiplierDelta: 0.5 }, totalPnl: 300, totalTrades: 8 },
      ],
    }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result }) }
    render(<GridSearchPanel {...props} />)
    // Grid rows table columns
    expect(screen.getByText('tpΔ')).toBeInTheDocument()
    expect(screen.getByText('slΔ')).toBeInTheDocument()
    expect(screen.getByText('P&L')).toBeInTheDocument()
    expect(screen.getByText('Trades')).toBeInTheDocument()
  })

  it('does not render grid rows table when rows is empty', () => {
    const result = { success: true, rows: [] }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.queryByText('tpΔ')).toBeNull()
  })
})

describe('GridSearchPanel — walk-forward folds table', () => {
  it('renders walk-forward folds table when walkForwardFolds is present', () => {
    const result = {
      success: true,
      rows: [],
      walkForwardSummary: {
        foldsCompleted: 3,
        foldsPlanned: 3,
        foldsWithOosBacktest: 3,
        aggregateOosPnl: 1500,
        aggregateOosTrades: 30,
      },
      walkForwardFolds: [
        {
          foldIndex: 1,
          trainFrom: '2024-01-01',
          trainTo: '2024-03-01',
          testFrom: '2024-03-01',
          testTo: '2024-04-01',
          oosTotalPnl: 500,
          oosTotalTrades: 10,
          isOptimizationWinner: true,
          bestIsParameters: { tpMultiplierDelta: 1.0, slMultiplierDelta: 0.5 },
        },
      ],
    }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result }) }
    render(<GridSearchPanel {...props} />)
    // Folds table column headers
    expect(screen.getByText('Train')).toBeInTheDocument()
    expect(screen.getByText('Test')).toBeInTheDocument()
    expect(screen.getByText('OOS PnL')).toBeInTheDocument()
  })

  it('renders walk-forward summary section', () => {
    const result = {
      success: true,
      rows: [],
      walkForwardSummary: {
        foldsCompleted: 3,
        foldsPlanned: 3,
        foldsWithOosBacktest: 2,
        aggregateOosPnl: 1200,
        aggregateOosTrades: 25,
      },
      walkForwardFolds: [],
    }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: result }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByText(/Walk-forward — OOS agregado/i)).toBeInTheDocument()
  })
})

describe('GridSearchPanel — retest result', () => {
  it('renders retest result section when retestData has success and improvement', () => {
    // retestData renders inside modalGridResult block — need a non-null result
    const minimalResult = { success: true, rows: [] }
    const retestData = {
      success: true,
      improvement: { pnlDiff: 300, winRateDiff: 5 },
      previous: { totalPnl: 1000, winRate: 0.6 },
      current: { totalPnl: 1300, winRate: 0.65 },
      singleTickerScoped: true,
      filterTicker: 'AAPL',
      appliedTpMultiplierDelta: 1.0,
      appliedSlMultiplierDelta: 0.5,
    }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: minimalResult, retestData }) }
    render(<GridSearchPanel {...props} />)
    expect(screen.getByText(/Retest \(solo simulación/i)).toBeInTheDocument()
  })

  it('renders retest error when retestError is set', () => {
    // retestError renders inside modalGridResult block — need a non-null result
    const minimalResult = { success: true, rows: [] }
    const props = { ...baseProps, gridSearch: makeGridSearch({ modalGridResult: minimalResult, retestError: 'Retest failed' }) }
    render(<GridSearchPanel {...props} />)
    const alerts = screen.getAllByRole('alert')
    const retestAlert = alerts.find(a => a.textContent === 'Retest failed')
    expect(retestAlert).toBeTruthy()
  })
})
