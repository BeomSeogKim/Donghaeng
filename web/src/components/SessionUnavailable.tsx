import { BrandMark } from './BrandMark'
import { buttonClassName } from './Button'
import { Screen } from './Screen'

/**
 * `GET /auth/me` failed with something that is not a 401.
 *
 * This screen exists to keep the app from telling a lie it cannot take back:
 * "you are signed out" and "we could not reach the server" are different
 * answers, and showing the login screen for the second sends a signed-in person
 * back through Google to fix a problem that was never theirs. A 500 says nothing
 * about what went wrong by design, so there is nothing to explain — only
 * something to try again.
 */
export function SessionUnavailable({ onRetry }: { onRetry: () => void }) {
  return (
    <Screen>
      <BrandMark heading />
      <div className="flex w-full flex-col items-center gap-4">
        <p className="text-body leading-body text-ink-muted">
          로그인 상태를 확인하지 못했습니다
        </p>
        <button className={buttonClassName('secondary')} onClick={onRetry} type="button">
          다시 시도
        </button>
      </div>
    </Screen>
  )
}
