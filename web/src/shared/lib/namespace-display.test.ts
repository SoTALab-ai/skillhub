import { describe, expect, it } from 'vitest'
import { getNamespaceBadgeLabel, getNamespaceDisplayName } from './namespace-display'

describe('namespace display helpers', () => {
  it('shows the configured display name for global', () => {
    expect(getNamespaceDisplayName('global')).toBe('SotaLab AI')
    expect(getNamespaceBadgeLabel('global')).toBe('SotaLab AI')
  })

  it('prefers an explicit API display name', () => {
    expect(getNamespaceDisplayName('team-a', 'Team A')).toBe('Team A')
    expect(getNamespaceBadgeLabel('team-a', 'Team A')).toBe('Team A')
  })

  it('keeps team slugs as technical badges when no display name is available', () => {
    expect(getNamespaceDisplayName('mino')).toBe('mino')
    expect(getNamespaceBadgeLabel('mino')).toBe('@mino')
  })
})
