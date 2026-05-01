import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import HealthPage from './HealthPage'

// ── Mocks ──────────────────────────────────────────────────────────────────

vi.mock('../api', () => ({
  healthApi: {
    getHealth:    vi.fn(),
    getLiveness:  vi.fn(),
    getReadiness: vi.fn(),
  },
}))

// lucide-react icons — keep render output predictable
vi.mock('lucide-react', () => ({
  CheckCircle: ({ color }) => <span data-testid="icon-check" data-color={color} />,
  XCircle:     ({ color }) => <span data-testid="icon-x"    data-color={color} />,
  Activity:    ()          => <span data-testid="icon-activity" />,
}))

// ── Helpers ────────────────────────────────────────────────────────────────

import { healthApi } from '../api'

const healthUp    = { status: 'UP' }
const healthDown  = { status: 'DOWN' }
const livenessUp  = { status: 'UP' }
const readinessUp = { status: 'UP' }

function setupHappyPath() {
  healthApi.getHealth.mockResolvedValue(healthUp)
  healthApi.getLiveness.mockResolvedValue(livenessUp)
  healthApi.getReadiness.mockResolvedValue(readinessUp)
}

// ── Tests ──────────────────────────────────────────────────────────────────

describe('HealthPage', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  // ── Loading state ──────────────────────────────────────────────────────

  it('muestra el estado de carga mientras el fetch no resuelve', () => {
    // API never resolves in this test
    healthApi.getHealth.mockReturnValue(new Promise(() => {}))
    healthApi.getLiveness.mockReturnValue(new Promise(() => {}))
    healthApi.getReadiness.mockReturnValue(new Promise(() => {}))

    render(<HealthPage />)

    expect(screen.getByText(/Loading health status/i)).toBeInTheDocument()
  })

  // ── Error state ────────────────────────────────────────────────────────

  it('muestra el error cuando el fetch falla', async () => {
    healthApi.getHealth.mockRejectedValue(new Error('Connection refused'))
    healthApi.getLiveness.mockResolvedValue(livenessUp)
    healthApi.getReadiness.mockResolvedValue(readinessUp)

    await act(async () => {
      render(<HealthPage />)
    })

    expect(screen.getByText(/Health Check Failed/i)).toBeInTheDocument()
    expect(screen.getByText('Connection refused')).toBeInTheDocument()
  })

  it('muestra el mensaje de error específico cuando el fetch falla', async () => {
    healthApi.getHealth.mockRejectedValue(new Error('HTTP 503: Service Unavailable'))
    healthApi.getLiveness.mockResolvedValue(livenessUp)
    healthApi.getReadiness.mockResolvedValue(readinessUp)

    await act(async () => {
      render(<HealthPage />)
    })

    expect(screen.getByText('HTTP 503: Service Unavailable')).toBeInTheDocument()
  })

  // ── Happy path — sistema UP ────────────────────────────────────────────

  it('muestra System Health con status UP cuando todo está bien', async () => {
    setupHappyPath()

    await act(async () => {
      render(<HealthPage />)
    })

    expect(screen.getByText('System Health')).toBeInTheDocument()
    expect(screen.getByText('Overall Status:')).toBeInTheDocument()
    // UP aparece 3 veces: overall + liveness + readiness
    expect(screen.getAllByText('UP').length).toBeGreaterThanOrEqual(1)
  })

  it('muestra el ícono CheckCircle verde cuando el sistema está UP', async () => {
    setupHappyPath()

    await act(async () => {
      render(<HealthPage />)
    })

    const icon = screen.getByTestId('icon-check')
    expect(icon).toBeInTheDocument()
    expect(icon).toHaveAttribute('data-color', '#3fb950')
  })

  it('muestra Liveness y Readiness con status UP', async () => {
    setupHappyPath()

    await act(async () => {
      render(<HealthPage />)
    })

    expect(screen.getByText('Liveness')).toBeInTheDocument()
    expect(screen.getByText('Readiness')).toBeInTheDocument()

    // Ambos status UP visibles (Overall + Liveness + Readiness = 3 veces "UP")
    const upCells = screen.getAllByText('UP')
    expect(upCells.length).toBeGreaterThanOrEqual(3)
  })

  // ── Sistema DOWN ───────────────────────────────────────────────────────

  it('muestra el ícono XCircle rojo cuando el sistema está DOWN', async () => {
    healthApi.getHealth.mockResolvedValue(healthDown)
    healthApi.getLiveness.mockResolvedValue({ status: 'DOWN' })
    healthApi.getReadiness.mockResolvedValue({ status: 'DOWN' })

    await act(async () => {
      render(<HealthPage />)
    })

    const icon = screen.getByTestId('icon-x')
    expect(icon).toBeInTheDocument()
    expect(icon).toHaveAttribute('data-color', '#f85149')
  })

  it('muestra status DOWN en overall cuando el sistema está DOWN', async () => {
    healthApi.getHealth.mockResolvedValue(healthDown)
    healthApi.getLiveness.mockResolvedValue({ status: 'DOWN' })
    healthApi.getReadiness.mockResolvedValue({ status: 'DOWN' })

    await act(async () => {
      render(<HealthPage />)
    })

    // DOWN aparece 3 veces: overall + liveness + readiness
    expect(screen.getAllByText('DOWN').length).toBeGreaterThanOrEqual(1)
  })

  it('aplica la clase "negative" al stat-value cuando el sistema está DOWN', async () => {
    healthApi.getHealth.mockResolvedValue(healthDown)
    healthApi.getLiveness.mockResolvedValue({ status: 'DOWN' })
    healthApi.getReadiness.mockResolvedValue({ status: 'DOWN' })

    await act(async () => {
      render(<HealthPage />)
    })

    // El primer stat-value del overall status debe tener clase "negative"
    const downValues = document.querySelectorAll('.stat-value.negative')
    expect(downValues.length).toBeGreaterThan(0)
  })

  // ── Grupos (opcional) ──────────────────────────────────────────────────

  it('muestra los grupos cuando health.groups está presente', async () => {
    healthApi.getHealth.mockResolvedValue({ status: 'UP', groups: ['liveness', 'readiness'] })
    healthApi.getLiveness.mockResolvedValue(livenessUp)
    healthApi.getReadiness.mockResolvedValue(readinessUp)

    await act(async () => {
      render(<HealthPage />)
    })

    expect(screen.getByText('Groups:')).toBeInTheDocument()
    expect(screen.getByText('liveness, readiness')).toBeInTheDocument()
  })

  it('no muestra la sección Groups cuando health.groups está ausente', async () => {
    healthApi.getHealth.mockResolvedValue({ status: 'UP' }) // sin groups
    healthApi.getLiveness.mockResolvedValue(livenessUp)
    healthApi.getReadiness.mockResolvedValue(readinessUp)

    await act(async () => {
      render(<HealthPage />)
    })

    expect(screen.queryByText('Groups:')).not.toBeInTheDocument()
  })

  // ── Liveness/Readiness con status desconocido ──────────────────────────

  it('muestra "Unknown" cuando liveness o readiness son null', async () => {
    healthApi.getHealth.mockResolvedValue(healthUp)
    healthApi.getLiveness.mockResolvedValue(null)
    healthApi.getReadiness.mockResolvedValue(null)

    await act(async () => {
      render(<HealthPage />)
    })

    const unknownCells = screen.getAllByText('Unknown')
    expect(unknownCells).toHaveLength(2)
  })

  // ── Polling ────────────────────────────────────────────────────────────

  it('llama a los tres endpoints al montar el componente', async () => {
    setupHappyPath()

    await act(async () => {
      render(<HealthPage />)
    })

    expect(healthApi.getHealth).toHaveBeenCalledTimes(1)
    expect(healthApi.getLiveness).toHaveBeenCalledTimes(1)
    expect(healthApi.getReadiness).toHaveBeenCalledTimes(1)
  })

  it('vuelve a llamar a los endpoints después de 5 segundos (polling)', async () => {
    setupHappyPath()

    await act(async () => {
      render(<HealthPage />)
    })

    expect(healthApi.getHealth).toHaveBeenCalledTimes(1)

    await act(async () => {
      vi.advanceTimersByTime(5000)
    })

    expect(healthApi.getHealth).toHaveBeenCalledTimes(2)
    expect(healthApi.getLiveness).toHaveBeenCalledTimes(2)
    expect(healthApi.getReadiness).toHaveBeenCalledTimes(2)
  })

  it('limpia el intervalo al desmontar el componente', async () => {
    setupHappyPath()
    const clearIntervalSpy = vi.spyOn(global, 'clearInterval')

    let unmount
    await act(async () => {
      const result = render(<HealthPage />)
      unmount = result.unmount
    })

    unmount()

    expect(clearIntervalSpy).toHaveBeenCalled()
    clearIntervalSpy.mockRestore()
  })

  // ── Recuperación de error ──────────────────────────────────────────────

  it('limpia el error si el polling posterior tiene éxito', async () => {
    // Primera llamada falla
    healthApi.getHealth.mockRejectedValueOnce(new Error('flaky'))
    healthApi.getLiveness.mockResolvedValue(livenessUp)
    healthApi.getReadiness.mockResolvedValue(readinessUp)

    await act(async () => {
      render(<HealthPage />)
    })

    expect(screen.getByText(/Health Check Failed/i)).toBeInTheDocument()

    // Segunda llamada (polling) tiene éxito
    healthApi.getHealth.mockResolvedValue(healthUp)

    await act(async () => {
      vi.advanceTimersByTime(5000)
    })

    expect(screen.queryByText(/Health Check Failed/i)).not.toBeInTheDocument()
    expect(screen.getByText('System Health')).toBeInTheDocument()
  })
})
