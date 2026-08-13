import { BrandMark } from '../components/BrandMark'
import { buttonClassName } from '../components/Button'
import { Screen } from '../components/Screen'
import { apiUrl } from '../lib/api'

/*
 * STARTING LOGIN IS A NAVIGATION, NOT A FETCH — which is why this is an <a> and
 * there is no onClick anywhere on this screen.
 *
 * `GET /oauth2/authorization/google` answers 302 to Google's consent screen. An
 * XHR cannot usefully follow that chain: it would fail CORS at the provider
 * rather than logging anyone in. The browser leaves, Google returns it to the
 * API's callback, and the API sets the cookie and redirects back to this app's
 * configured origin — which is server configuration, never a parameter we send,
 * so there is nothing here to pass along and no returnTo to build.
 *
 * ONE PROVIDER, AND NO MEMORY OF IT YET. "마지막에 쓴 provider 기억" is part of
 * #38 and is deliberately not built: with a single button there is nothing to
 * remember and nothing a wrong guess could cost, so it would ship as an
 * untestable write to storage. It becomes real work — and gets a real design —
 * when #89 adds 카카오 and 네이버.
 */
export function LoginPage() {
  return (
    <Screen>
      <BrandMark />
      <p className="text-body leading-body text-ink-muted">
        하객 명부와 인원수를 한 화면에서 관리합니다.
      </p>
      <a
        className={`${buttonClassName('primary')} w-full`}
        href={apiUrl(GOOGLE_LOGIN_PATH)}
      >
        구글로 로그인
      </a>
    </Screen>
  )
}

const GOOGLE_LOGIN_PATH = '/oauth2/authorization/google'
