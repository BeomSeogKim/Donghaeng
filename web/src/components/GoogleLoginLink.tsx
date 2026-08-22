import { apiUrl } from '../lib/api'
import { buttonClassName } from './Button'

/**
 * 구글로 로그인 — the one control that starts a login, and the one place its URL
 * is written.
 *
 * IT IS AN `<a>`, AND WHY IS `pages/LoginPage.tsx`'s COMMENT — the whole round
 * trip is argued there and is not repeated here.
 *
 * IT IS A COMPONENT BECAUSE TWO SCREENS START A LOGIN: 로그인, and 초대 수락,
 * which is reached from a KakaoTalk link by someone who has no session yet. A
 * path string copied to a second call site is a path that gets fixed at one of
 * them.
 */
export function GoogleLoginLink() {
  return (
    <a
      className={`${buttonClassName('primary')} w-full`}
      href={apiUrl(GOOGLE_LOGIN_PATH)}
    >
      구글로 로그인
    </a>
  )
}

const GOOGLE_LOGIN_PATH = '/oauth2/authorization/google'
