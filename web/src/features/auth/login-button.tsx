import { useTranslation } from 'react-i18next'
import { getOAuthFallbackRuntimeConfig } from '@/api/client'
import { Button } from '@/shared/ui/button'
import { withBasePath } from '@/shared/lib/base-path'
import { useAuthMethods } from './use-auth-methods'

interface LoginButtonProps {
  returnTo?: string
}

/**
 * Returns the appropriate icon for a given OAuth provider.
 */
function OAuthIcon({ provider }: { provider: string }) {
  const normalizedProvider = provider.toLowerCase()
  return (
    <img
      src={withBasePath(`/${normalizedProvider}-logo.svg`)}
      alt={provider}
      className="w-5 h-5 mr-3"
    />
  )
}

/**
 * Renders OAuth login buttons from the auth-method catalog returned by the backend.
 */
export function LoginButton({ returnTo }: LoginButtonProps) {
  const { t } = useTranslation()
  const { data, isLoading } = useAuthMethods(returnTo)
  const fallback = getOAuthFallbackRuntimeConfig()

  const catalogProviders = (data ?? []).filter((method) => method.methodType === 'OAUTH_REDIRECT')
  const providers = catalogProviders.length > 0
    ? catalogProviders
    : fallback
      ? [{
          id: `runtime-oauth-${fallback.provider}`,
          methodType: 'OAUTH_REDIRECT',
          provider: fallback.provider,
          displayName: fallback.displayName,
          actionUrl: fallback.actionUrl,
        }]
      : []

  if (isLoading && providers.length === 0) {
    return (
      <div className="space-y-3">
        <Button className="w-full h-12" disabled>
          <div className="w-5 h-5 rounded-full animate-shimmer mr-3" />
          {t('loginButton.loading')}
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-3">
      {providers.map((provider) => (
        <Button
          key={provider.id}
          className="w-full h-12 text-base"
          variant="outline"
          onClick={() => {
            window.location.href = withBasePath(provider.actionUrl)
          }}
        >
          <OAuthIcon provider={provider.provider} />
          {t('loginButton.loginWith', { name: provider.displayName })}
        </Button>
      ))}
    </div>
  )
}
