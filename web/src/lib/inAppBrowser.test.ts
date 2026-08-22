import { describe, expect, it } from 'vitest'
import { isInAppBrowser } from './inAppBrowser'

/*
 * The user agents this has to tell apart, written out rather than described.
 *
 * WHAT THE MATRIX IS PROTECTING is not the notice — it is the login button
 * beside it. A match that is too broad puts "구글 로그인이 막힙니다" in front of
 * somebody whose login works perfectly, on the one screen where being confusing
 * costs a couple their shared ledger. Chrome Custom Tabs and
 * SFSafariViewController are ordinary browsers to Google and are NOT blocked,
 * and they are indistinguishable from the system browser here — which is why
 * this can never become a `wv` catch-all.
 */

describe('in-app browsers, where Google refuses the OAuth round trip', () => {
  const cases: Record<string, string> = {
    'KakaoTalk on Android':
      'Mozilla/5.0 (Linux; Android 14; SM-S928N Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/122.0.6261.119 Mobile Safari/537.36 KAKAOTALK 10.7.0',
    'KakaoTalk on iOS':
      'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 KAKAOTALK 10.7.0',
    'the NAVER app':
      'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 NAVER(inapp; search; 2000; 12.9.0)',
    Instagram:
      'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/122.0.0.0 Mobile Safari/537.36 Instagram 320.0.0.42.101',
    Facebook:
      'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/450.0.0.35.108]',
    LINE: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Line/14.3.0',
  }

  for (const [name, userAgent] of Object.entries(cases)) {
    it(`matches ${name}`, () => expect(isInAppBrowser(userAgent)).toBe(true))
  }
})

describe('browsers that finish a Google login, which must never see the notice', () => {
  const cases: Record<string, string> = {
    'Chrome on Android':
      'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36',
    'Safari on iOS':
      'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1',
    // A Custom Tab reports the plain Chrome agent — no token distinguishes it,
    // and it does not need one: Google accepts it.
    'a Chrome Custom Tab':
      'Mozilla/5.0 (Linux; Android 14; SM-S928N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36',
    'Samsung Internet':
      'Mozilla/5.0 (Linux; Android 14; SM-S928N) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/24.0 Chrome/117.0.0.0 Mobile Safari/537.36',
    'Whale on Android':
      'Mozilla/5.0 (Linux; Android 14; SM-S928N) AppleWebKit/537.36 (KHTML, like Gecko) Whale/3.24.223.21 Crosswalk/29.122.0.0 Mobile Safari/537.36',
    'a desktop browser':
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
  }

  for (const [name, userAgent] of Object.entries(cases)) {
    it(`leaves ${name} alone`, () => expect(isInAppBrowser(userAgent)).toBe(false))
  }
})
