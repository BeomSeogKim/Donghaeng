import { type FormEvent, type ReactNode, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { BrandMark } from '../components/BrandMark'
import { buttonClassName } from '../components/Button'
import { Field } from '../components/Field'
import { GoogleLoginLink } from '../components/GoogleLoginLink'
import { LogoutButton } from '../components/LogoutButton'
import { Screen } from '../components/Screen'
import { useJoinWedding } from '../hooks/useJoinWedding'
import { useSession } from '../hooks/useSession'
import { ApiError } from '../lib/api'
import { isInAppBrowser } from '../lib/inAppBrowser'
import { nameError } from '../lib/name'
import { ledgerPath } from '../lib/routes'

/*
 * 초대 수락 — where the KakaoTalk link lands, and the only screen a signed-out
 * person may open besides 로그인.
 *
 * THE TOKEN NEVER BECOMES A URL PARAMETER, anywhere in this flow. It arrives in
 * the fragment, which no server ever sees; it waits in `sessionStorage` while
 * the browser is away at Google; it leaves in the body of one POST. There is no
 * `returnTo` and none is coming — that would be a second place a token-bearing
 * URL could be logged, and refusing to build one is the same decision as the
 * fragment (notes/2026-08-22-decision-the-invite-link.md §§2-3).
 *
 * 수락은 버튼이 아니라 폼이다. `POST /weddings/join` requires a `name` and it is
 * the ACCEPTING person's own: the seat is empty precisely because nobody types
 * anybody else's name. So a partner who arrives here signs in, then says who
 * they are, and only then is anything written.
 *
 * THE FOUR REFUSALS ARE FOUR ANSWERS, not two. Two 404s that differ by whether
 * a new link would help, and two 409s that differ by whose problem it is —
 * "you already have a ledger" is not a failure at all
 * (docs/api-spec.md § POST /weddings/join).
 */
export function AcceptInvitePage({ token }: { token: string | null }) {
  const session = useSession()

  /*
   * THE TOKEN IS A PROP, READ BY `App` ABOVE THE SESSION GATE. This screen does
   * not mount until `GET /auth/me` has answered, so reading the fragment here
   * would leave it in the address bar for that whole round trip — and forever
   * when the read fails, since that branch never renders the route table
   * (`hooks/useInviteToken.ts`). There is no session branch here either, for
   * the same reason: `App` has already answered it, and a second copy would be
   * unreachable code claiming to handle something.
   */

  /*
   * THE DEAD END KakaoTalk'S WEBVIEW CAN STILL PRODUCE, and the one place it
   * gets to say something useful. If the OAuth round trip left this app's
   * browser, the tab that came back is not the tab that stashed the token and
   * `sessionStorage` is empty. The failure is SAFE — the token went nowhere and
   * nobody is half-seated — but it is a dead end unless the screen says the
   * link itself still works, which for the rest of the day it does.
   *
   * It is also what somebody who typed the path sees, and the same sentence is
   * the right answer to both.
   */
  if (token === null) {
    return (
      <Notice signedIn={session.data !== null} title="초대 정보가 없습니다">
        <p className="text-body leading-body text-ink-muted">
          받은 초대 링크를 다시 열어 주세요. 링크는 만든 지 하루 동안 쓸 수 있습니다.
        </p>
      </Notice>
    )
  }

  if (session.data === null) return <SignInFirst />

  return <AcceptForm token={token} />
}

/**
 * 로그인이 먼저다 — and it is the ordinary case, not an interruption: somebody
 * tapping a link in a KakaoTalk room has no reason to be holding our session.
 *
 * The token is already stashed by the time this renders, so nothing about the
 * login has to carry it and nothing about it changes (`useInviteToken`).
 */
function SignInFirst() {
  return (
    <Screen>
      <BrandMark />
      <div className="flex w-full flex-col gap-3">
        <h1 className="font-display text-title tracking-display">초대를 받았습니다</h1>
        <p className="text-body leading-body text-ink-muted">
          파트너가 만든 원장에 함께 들어갑니다. 먼저 로그인해 주세요.
        </p>
      </div>
      <div className="flex w-full flex-col gap-4">
        <InAppBrowserNotice />
        <GoogleLoginLink />
      </div>
    </Screen>
  )
}

/**
 * 앱 안에서 열린 브라우저 — the one failure this screen can see coming.
 *
 * Google refuses OAuth inside an embedded browser and answers
 * `disallowed_useragent`, and KakaoTalk's in-app browser is one — so tapping
 * 로그인 here does not come back at all, it stops at Google's own error page
 * where nothing of ours can speak (`lib/inAppBrowser.ts` carries the evidence).
 * This is the last screen that can say anything, so it says it before the tap
 * rather than after.
 *
 * THE RECOVERY IS THE MESSAGE, NOT THIS PAGE'S URL. The fragment has already
 * been cleared from the address bar, so "open this page in another browser"
 * would hand over a link with no token in it. The chat room still has the whole
 * link, and that is what the copy points at.
 *
 * IT IS SHOWN BESIDE THE BUTTON, NEVER INSTEAD OF IT. The detection is a user
 * agent match and can be wrong in both directions; taking the login away from
 * somebody whose login works would be the worse mistake.
 */
function InAppBrowserNotice() {
  if (!isInAppBrowser(navigator.userAgent)) return null

  return (
    <div className="flex flex-col gap-2 border border-line bg-surface px-4 py-3">
      <p className="text-body leading-body text-ink">
        앱 안에서 열린 브라우저에서는 구글 로그인이 막힙니다.
      </p>
      <p className="text-body leading-body text-ink-muted">
        대화방에서 초대 링크를 길게 눌러 복사한 뒤, 크롬이나 사파리 주소창에 붙여넣어
        주세요.
      </p>
    </div>
  )
}

/**
 * 내 이름을 적고 자리에 앉는다.
 *
 * THE NAME RULE IS `lib/name.ts` AND IT IS THE SAME ONE 웨딩 만들기 USES — the
 * server validates the same column the same way for both, and the spec asks for
 * one client-side rule covering both screens.
 */
function AcceptForm({ token }: { token: string }) {
  const navigate = useNavigate()
  const join = useJoinWedding()
  const [name, setName] = useState('')

  /*
   * Errors are derived, not stored: they appear on the first submit and then
   * disappear as the field is fixed, with no second copy of the form's state to
   * keep in step.
   */
  const [submitted, setSubmitted] = useState(false)
  // Trimmed here because the server measures BEFORE its own trim (lib/name.ts).
  const sending = name.trim()
  const error = submitted ? nameError(sending) : undefined

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    // A submit is a user action, so it is handled here — never in an Effect
    // watching a flag.
    event.preventDefault()
    setSubmitted(true)

    /*
     * Serialising mutations DELAYS a second press and never refuses one, so
     * this guard is the only thing that stops a double press becoming two
     * requests (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md)
     * — and here the second request would be handed a token the first one just
     * spent, so the couple would be told the seat is gone by their own tap.
     */
    if (join.isPending) return
    if (nameError(sending) !== undefined) return

    join.mutate(
      { token, name: sending },
      // 원장 is where a seated person belongs, and `replace` so Back does not
      // return to an accept screen holding a token that is now spent.
      { onSuccess: () => navigate(ledgerPath, { replace: true }) },
    )
  }

  const refusal = join.isError ? refusalFor(join.error) : null

  // A settled refusal takes the form away rather than leaving a button that can
  // only be refused again. The token is already gone (`useJoinWedding`).
  if (refusal?.settled === true) {
    return (
      <Notice signedIn title={refusal.title}>
        {refusal.detail !== undefined && (
          <p className="text-body leading-body text-ink-muted">{refusal.detail}</p>
        )}
        {refusal.ledger && (
          <Link className={buttonClassName('secondary')} to={ledgerPath}>
            내 원장 열기
          </Link>
        )}
      </Notice>
    )
  }

  return (
    <Screen>
      <div className="flex w-full flex-col gap-3">
        <h1 className="font-display text-title tracking-display">초대 수락</h1>
        <p className="text-body leading-body text-ink-muted">
          원장에 표시될 본인 이름을 적어 주세요. 상대방 이름은 적지 않습니다.
        </p>
      </div>

      <form className="flex w-full flex-col gap-5" onSubmit={handleSubmit}>
        <Field
          error={error}
          id="my-name"
          label="내 이름"
          onChange={(event) => setName(event.target.value)}
          type="text"
          value={name}
        />

        {refusal !== null && (
          // Announced, because it appears away from the button that was just
          // pressed and nothing else on the screen changes to say so.
          <p className="text-body leading-body text-danger" role="alert">
            {refusal.title}
          </p>
        )}

        <button
          className={`${buttonClassName('primary')} w-full`}
          disabled={join.isPending}
          type="submit"
        >
          수락
        </button>
      </form>

      <Exit />
    </Screen>
  )
}

/**
 * THE EXIT THIS SCREEN WOULD OTHERWISE NOT HAVE, and it is not decoration.
 *
 * A signed-in person here holds no wedding — that is what sent them here — so
 * 원장 sends them straight back, and 웨딩 만들기 is the one screen they must not
 * be handed. Without this they are parked with exactly one thing to press and
 * no way to be anybody else.
 *
 * AND "I SIGNED IN WITH THE WRONG GOOGLE ACCOUNT" IS THE CASE IT SERVES. The
 * token deliberately survives a sign-out (`lib/invite.ts`), so signing out here
 * and back in as the right account lands on this same screen with the same
 * invite still waiting — which only works if there is a way to sign out.
 */
function Exit() {
  return <LogoutButton className="flex flex-col items-center gap-2 text-center" />
}

/**
 * A refusal, in the two things a screen does with one: what it says, and
 * whether there is anything left to press.
 */
type Refusal = {
  title: string
  detail?: string
  /** Whether pressing again could ever change the answer. */
  settled: boolean
  /** Whether to offer the ledger this person turns out to already have. */
  ledger?: boolean
}

/**
 * What a refused accept says, chosen from `code` and nothing else.
 *
 * THE FOUR SETTLED CODES ARE FOUR DIFFERENT SENTENCES, and the differences are
 * the point rather than polish:
 *
 * - `INVITE_EXPIRED` is the **common** one, not an edge case: a link lives one
 *   day, so a partner who opens it the next evening lands exactly here — and
 *   the recovery is one tap in 설정, on the other person's phone.
 * - `INVITE_NOT_FOUND` says nothing more, deliberately. It covers unknown,
 *   wrong, already spent and replaced-by-a-재발급 alike, and telling those apart
 *   is what somebody guessing tokens would want (docs/api-spec.md).
 * - `ALREADY_IN_A_WEDDING` is **not a failure**. This person has their own
 *   ledger, and the spec's recovery is to open it — the same answer `#164` owes
 *   the create side, from the same code and the same check.
 * - `PARTNER_ALREADY_JOINED` is the seat being gone. There is no recovery to
 *   offer and inventing one would be a lie.
 *
 * AN UNRECOGNISED `code` IS STILL SETTLED, with generic words: a 404 or a 409
 * from this endpoint means the attempt is over whatever it was called.
 *
 * BUT A `code` OF `null` IS NOT, and that is the distinction rather than a
 * hedge. `apiError` reports `null` for a 4xx that is not a problem document —
 * a proxy or the servlet container answered and the application never saw the
 * request (`lib/api.ts`) — so nothing has been decided about this token. It is
 * the retryable failure, which is also what keeps the screen honest with
 * `useJoinWedding`: that hook keeps the token in exactly this case, and a form
 * taken away from somebody still holding a good invite would be a dead end we
 * invented.
 *
 * A 401 PRODUCES NOTHING AT ALL: the session was written to `null`, the login
 * screen is replacing this one, and the token is deliberately still in
 * `sessionStorage` — signing in again is what this flow was doing anyway.
 */
function refusalFor(error: unknown): Refusal | null {
  if (!(error instanceof ApiError)) return UNAVAILABLE
  if (error.status === 401) return null
  if (error.status === 400) return { title: '이름을 다시 확인해 주세요.', settled: false }
  if (error.status !== 404 && error.status !== 409) return UNAVAILABLE
  if (error.code === null) return UNAVAILABLE

  switch (error.code) {
    case 'INVITE_EXPIRED':
      return {
        title: '링크가 만료되었습니다',
        detail: '파트너에게 새 링크를 요청하세요.',
        settled: true,
      }
    case 'INVITE_NOT_FOUND':
      return { title: '이 링크는 사용할 수 없습니다', settled: true }
    case 'ALREADY_IN_A_WEDDING':
      return {
        title: '이미 다른 웨딩에 속해 있습니다',
        detail: '한 사람은 하나의 웨딩에만 속할 수 있습니다.',
        settled: true,
        ledger: true,
      }
    case 'PARTNER_ALREADY_JOINED':
      return {
        title: '이 자리는 이미 채워졌습니다',
        detail: '두 사람 모두 이미 참여했습니다.',
        settled: true,
      }
    default:
      return { title: '초대를 수락하지 못했습니다', settled: true }
  }
}

/**
 * The one that is not the server's answer: a dropped connection, or a 5xx that
 * says nothing about what went wrong by design. There is nothing to explain and
 * only something to try again, so the form stays.
 */
const UNAVAILABLE: Refusal = {
  title: '초대를 수락하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  settled: false,
}

/**
 * The EmptyState part — a bordered block on surface, never a floating card, and
 * never an illustration: a tool has no reason to be cheerful about a refusal.
 *
 * The exit sits OUTSIDE the block, the way 웨딩 만들기 holds it: it is a way off
 * the screen rather than one of the screen's answers.
 */
function Notice({
  children,
  signedIn,
  title,
}: {
  children?: ReactNode
  signedIn: boolean
  title: string
}) {
  return (
    <Screen>
      <div className="flex w-full flex-col items-start gap-3 border border-line bg-surface px-6 py-8">
        <h1 className="text-lead font-semibold leading-snug">{title}</h1>
        {children}
      </div>
      {signedIn && <Exit />}
    </Screen>
  )
}
