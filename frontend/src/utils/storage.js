export const LS = {
  get: (key, fallback) => {
    try {
      const v = localStorage.getItem(key)
      return v !== null ? JSON.parse(v) : fallback
    } catch {
      return fallback
    }
  },
  set: (key, value) => {
    try {
      localStorage.setItem(key, JSON.stringify(value))
      window.dispatchEvent(new CustomEvent('app-ls-change', { detail: { key } }))
    } catch (e) {
      console.warn('LS.set failed', key, e)
    }
  },
  remove: (key) => {
    try {
      localStorage.removeItem(key)
    } catch {}
  }
}
