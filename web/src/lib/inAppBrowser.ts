/*
 * Whether this page is running inside an app's own browser — KakaoTalk's above
 * all, because that is where the invite link arrives.
 *
 * THIS IS THE DEAD END `#9` OWNS, AND IT IS NOT HYPOTHETICAL. Google refuses
 * OAuth in an embedded user agent and answers `disallowed_useragent`
 * (developers.google.com/identity/protocols/oauth2/native-app), and KakaoTalk's
 * in-app browser is a WebView on both platforms. So a partner who taps the link
 * inside KakaoTalk cannot finish a Google login there at all — they do not even
 * reach the "the tab that came back is a different tab" failure, they stop at
 * Google's own error page, where nothing of ours can speak to them. The only
 * screen that can warn them is the one BEFORE the login tap, which is why this
 * exists.
 *
 * IT IS A WHITELIST AND NEVER A CATCH-ALL. Chrome Custom Tabs and
 * SFSafariViewController are ordinary browsers to Google and are not blocked,
 * and they are indistinguishable from the system browser by user agent — so
 * matching anything broader (`wv`, a missing `Safari` token) would put a
 * confusing notice in front of people whose login works fine.
 *
 * THE RECOVERY THIS SUPPORTS DOES NOT DEPEND ON THE FRAGMENT. The address bar
 * has already been cleared by the time anyone reads the notice, so the way out
 * is the message in the chat room, which still carries the whole link.
 */

const IN_APP = /KAKAOTALK|Instagram|FBAN|FBAV|Line\/|NAVER\(inapp|DaumApps|Band\//i

export function isInAppBrowser(userAgent: string): boolean {
  return IN_APP.test(userAgent)
}
