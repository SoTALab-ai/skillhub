const SYSTEM_NAMESPACE_DISPLAY_NAMES: Readonly<Record<string, string>> = {
  global: 'SotaLab AI',
}

export function getNamespaceDisplayName(slug: string, displayName?: string): string {
  const explicitDisplayName = displayName?.trim()
  if (explicitDisplayName) {
    return explicitDisplayName
  }

  const normalizedSlug = slug.trim().toLowerCase()
  return SYSTEM_NAMESPACE_DISPLAY_NAMES[normalizedSlug] ?? slug
}

export function getNamespaceBadgeLabel(slug: string, displayName?: string): string {
  const resolvedDisplayName = getNamespaceDisplayName(slug, displayName)
  return resolvedDisplayName === slug ? `@${slug}` : resolvedDisplayName
}
