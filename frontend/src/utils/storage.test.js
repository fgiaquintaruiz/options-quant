import { describe, it, expect, beforeEach } from 'vitest'
import { LS } from './storage.js'

describe('LS', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('returns fallback when key is missing', () => {
    expect(LS.get('missing', 42)).toBe(42)
  })

  it('round-trips JSON values', () => {
    LS.set('k', { a: 1 })
    expect(LS.get('k', null)).toEqual({ a: 1 })
  })

  it('remove clears key', () => {
    LS.set('k', 1)
    LS.remove('k')
    expect(LS.get('k', 'x')).toBe('x')
  })

  it('returns fallback when stored value is not valid JSON', () => {
    localStorage.setItem('bad', 'not-json')
    expect(LS.get('bad', 7)).toBe(7)
  })

  it('set swallows quota / storage errors', () => {
    const orig = Storage.prototype.setItem
    Storage.prototype.setItem = () => {
      throw new Error('quota')
    }
    expect(() => LS.set('k', 1)).not.toThrow()
    Storage.prototype.setItem = orig
  })

  it('remove swallows storage errors', () => {
    const orig = Storage.prototype.removeItem
    Storage.prototype.removeItem = () => {
      throw new Error('denied')
    }
    expect(() => LS.remove('k')).not.toThrow()
    Storage.prototype.removeItem = orig
  })
})
