import { GoogleLoginLink } from '../components/GoogleLoginLink'
import { LoginFace, Screen } from '../components/Screen'
import { type LoginFailure, useLoginFailure } from '../hooks/useLoginFailure'

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
  const failure = useLoginFailure()

  return (
    /*
     * 자적 위의 금박 — the one screen that shows what this palette came from,
     * before the couple has ever seen a number
     * (notes/2026-08-23-decision-the-form-language.md). It is the same 자적 the
     * ledger's 굽 is, so the entry flow and the ledger are one garment.
     */
    <Screen aside={<LoginFace>몇 명이 오는지, 마지막까지 정확하게.</LoginFace>}>
      <div className="flex flex-col gap-3">
        {/* One of RIDIBatang's three places. There is no headcount here, so
            this is the display face's single appearance on the pane. */}
        <h2 className="font-display text-title leading-snug font-bold tracking-display">
          시작하기
        </h2>
        <p className="text-body leading-body text-ink-muted">
          계정 하나면 됩니다. 초대를 보내면 상대방도 같은 명부로 들어옵니다.
        </p>
      </div>
      <div className="flex w-full flex-col gap-4">
        {failure !== null && (
          <div className="flex flex-col gap-2">
            {FAILURE_COPY[failure].map((line) => (
              <p className="text-body leading-body text-ink-muted" key={line}>
                {line}
              </p>
            ))}
          </div>
        )}
        <GoogleLoginLink />
      </div>
      <p className="text-meta leading-body text-ink-faint">
        계속하면 이용약관과 개인정보 처리방침에 동의하게 됩니다.
      </p>
    </Screen>
  )
}

/*
 * Every word a failed callback can put on this screen — one constant per code,
 * because the code itself is switched on and never rendered (the reason is in
 * hooks/useLoginFailure.ts, and it is not a precaution).
 *
 * BOTH ARE THE SAME MUTED LINE SessionUnavailable USES, and that is the whole
 * treatment: no fill, no heading, no live region. `denied` is a person who
 * changed their mind at Google — a normal path that must not be dressed as a
 * failure — and `failed` is in exactly SessionUnavailable's position: we could
 * not do it and cannot say why, so there is nothing to explain and only
 * something to try again. A soft-filled block with a bold heading would be an
 * eleventh component the closed v1 inventory does not have (design/AGENTS.md),
 * bought for a screen that already has a calmer way to say this.
 *
 * Saying nothing at all was the other option and is worse: a person who
 * cancelled would land on an unchanged screen unable to tell whether their
 * click did anything, which is what #109 was opened about. "무엇 때문인지는 알
 * 수 없습니다" stays for the same reason the vocabulary is two codes — it is
 * the honest sentence, not a hedge.
 */
const FAILURE_COPY: Record<LoginFailure, readonly string[]> = {
  denied: ['로그인을 취소했습니다'],
  failed: [
    '로그인하지 못했습니다',
    '무엇 때문인지는 알 수 없습니다. 다시 시도해 주세요.',
  ],
}
