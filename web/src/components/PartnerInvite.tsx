import { type ReactNode, useState } from 'react'
import { type IssuedInvite, useIssueInvite } from '../hooks/useIssueInvite'
import { useWeddings, type Wedding } from '../hooks/useWeddings'
import { ApiError } from '../lib/api'
import { inviteLink } from '../lib/invite'
import { buttonClassName } from './Button'

/*
 * 설정 · 파트너 초대 — 빈 자리를 채울 링크를 만든다.
 *
 * A WEDDING IS TWO SEATS, MADE TOGETHER, and an empty one is a partner who has
 * not arrived yet. So this screen does not *create* a membership — it mints the
 * link that fills a seat that already exists, and there is no side to choose:
 * which seat is waiting was settled when the wedding was made.
 *
 * THE LINK IS SHOWN ONCE AND CANNOT BE FETCHED BACK. Only a hash of the token
 * is stored, so there is no "yesterday's link" to display and no endpoint that
 * could serve one; `expiresAt` is memory-only for the same reason. Everything
 * here is therefore built around ONE affordance — 재발급 — rather than around a
 * link the screen could re-render after a reload
 * (notes/2026-08-22-decision-the-partner-invite.md §5).
 *
 * WHAT DECIDES WHETHER TO OFFER IT AT ALL IS `seats[].name`, the only signal
 * the API gives. A wedding with two seated people has nobody to invite, and
 * that is a screen state rather than a rule enforced twice — the 409 is what a
 * STALE tab gets, and it is handled below rather than prevented.
 */
export function PartnerInvite() {
  const weddings = useWeddings()

  /*
   * THE ISSUED LINK LIVES ABOVE EVERY BRANCH BELOW IT, and that placement is
   * the whole correctness of this file (found in review of `#182`).
   *
   * IT IS THE ONE VALUE ON THIS SCREEN NO READ CAN RECONSTRUCT. The server kept
   * only a hash, so there is no endpoint that returns it and never will be —
   * which means a component that unmounts loses it PERMANENTLY, and the couple's
   * only recovery is 재발급, killing the link they may already have pasted into
   * KakaoTalk.
   *
   * IT USED TO HANG OFF THE SHARED `GET /weddings` SUCCEEDING, and that read
   * fails in the ordinary course of using this screen: the couple switches to
   * KakaoTalk to paste the link and comes back, which is exactly when
   * `refetchOnWindowFocus` fires — and `staleTime: 0` means it really
   * refetches. One 5xx on that refetch put this query into `error` while
   * keeping its data, unmounting the subtree the token lived in. The screen
   * says "이 화면을 벗어나면 링크를 다시 볼 수 없습니다" — and the couple had
   * not left the screen.
   *
   * So the rule is narrow and structural: **nothing that can be re-fetched may
   * sit between this state and the render.**
   */
  const [issued, setIssued] = useState<IssuedInvite | null>(null)
  const link = issued === null ? null : <IssuedLink issued={issued} key={issued.token} />

  /*
   * NEITHER STATE CARRIES A RETRY BUTTON, and that is deliberate: this section
   * and 웨딩 정보 above it read the same `GET /weddings`, so one failed read
   * would otherwise put two failure blocks and two 다시 시도 buttons on one
   * screen for one thing that went wrong. The section above owns the retry.
   *
   * EACH ONE STILL RENDERS THE LINK. A read that is pending, failed, or empty
   * says nothing about a token this couple is holding in their hand.
   */
  if (weddings.isPending) {
    return (
      <div className="flex flex-col gap-4">
        {link}
        <p className="text-body leading-body text-ink-muted">불러오는 중입니다</p>
      </div>
    )
  }
  if (weddings.isError) {
    return (
      <div className="flex flex-col gap-4">
        {link}
        <p className="text-body leading-body text-ink-muted">
          결혼식 정보를 불러오지 못했습니다
        </p>
      </div>
    )
  }

  // An empty list means there is no wedding to invite anybody into, and 웨딩
  // 정보 above has already sent this person to 웨딩 만들기.
  const wedding = weddings.data[0]
  if (wedding === undefined) return link

  return <Invite issued={issued} link={link} onIssued={setIssued} wedding={wedding} />
}

function Invite({
  issued,
  link,
  onIssued,
  wedding,
}: {
  issued: IssuedInvite | null
  /** Rendered by the parent, so it survives every branch above this one. */
  link: ReactNode
  onIssued: (invite: IssuedInvite) => void
  wedding: Wedding
}) {
  const issue = useIssueInvite(wedding.id)

  // The seat that has nobody in it. `name` is optional AND nullable in the
  // document because springdoc leaves a nullable Kotlin property out of
  // `required`; the server always sends the key with `null` in it, and `== null`
  // reads the same on either (docs/api-spec.md § GET /weddings).
  const waiting = wedding.seats.some((seat) => seat.name == null)

  if (!waiting) {
    return (
      <div className="flex flex-col gap-2">
        {link}
        <p className="text-body leading-body text-ink">두 사람 모두 참여했습니다</p>
        <p className="text-body leading-body text-ink-muted">
          초대할 자리가 남아 있지 않습니다.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-body leading-body text-ink-muted">
        링크를 받은 사람이 로그인하면 이 하객 명부에 함께 들어옵니다. 링크는 하루 동안 쓸
        수 있고, 새로 만들면 이전 링크는 바로 쓸 수 없게 됩니다.
      </p>

      {link}

      <button
        className={`${buttonClassName(issued === null ? 'primary' : 'secondary')} self-start`}
        /*
         * Serialising mutations DELAYS a second press and never refuses one, so
         * this is the only thing that stops a double press minting twice — and
         * the second mint would kill the link the couple just watched us make
         * (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
         */
        disabled={issue.isPending}
        onClick={() =>
          issue.mutate(undefined, { onSuccess: (invite) => onIssued(invite) })
        }
        type="button"
      >
        {issued === null ? '초대 링크 만들기' : '새 링크 만들기'}
      </button>

      {issue.isError && failureMessage(issue.error, waiting) !== null && (
        // Announced: it appears away from the button that was just pressed, and
        // nothing else on the screen changes to say so.
        <p className="text-body leading-body text-danger" role="alert">
          {failureMessage(issue.error, waiting)}
        </p>
      )}
    </div>
  )
}

/**
 * The link itself, and the two things a couple has to know about it while it is
 * on screen: when it stops working, and that this is the only time they will
 * see it.
 *
 * 복사 IS NOT A NICETY HERE. Mobile is the primary device and this link is
 * pasted into KakaoTalk — selecting sixty characters of base64 with a thumb is
 * the difference between one tap and giving up.
 */
function IssuedLink({ issued }: { issued: IssuedInvite }) {
  const url = inviteLink(issued.token)
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null)

  async function copy() {
    try {
      await navigator.clipboard.writeText(url)
      setCopied('yes')
    } catch {
      // A browser with no clipboard permission, or none at all. The link is on
      // screen either way, so the honest answer is to say so and name the way
      // out rather than to leave a button that silently did nothing.
      setCopied('no')
    }
  }

  return (
    <div className="flex flex-col gap-3 border border-line bg-surface px-4 py-3">
      {/* A metadata fragment, never a sentence — 13px is what that is for. */}
      <p className="select-all break-all text-meta leading-snug text-ink">{url}</p>

      <div className="flex flex-wrap items-center gap-3">
        <button
          className={buttonClassName('secondary')}
          onClick={() => void copy()}
          type="button"
        >
          링크 복사
        </button>
        {copied !== null && (
          <p className="text-meta text-ink-muted" role="status">
            {copied === 'yes'
              ? '복사했습니다'
              : '복사하지 못했습니다. 길게 눌러 복사해 주세요.'}
          </p>
        )}
      </div>

      {/* `expiresAt` IS RENDERED, never a duration computed here — the server's
          answer is the one that decides when the link dies. */}
      <p className="text-meta leading-snug text-ink-muted">
        {expires(issued.expiresAt)}까지 쓸 수 있습니다.
      </p>
      <p className="text-meta leading-snug text-ink-muted">
        이 화면을 벗어나면 링크를 다시 볼 수 없습니다. 지금 복사해서 보내 주세요.
      </p>
    </div>
  )
}

/** The instant the API published, in the reader's own time zone. */
function expires(expiresAt: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'long',
    timeStyle: 'short',
  }).format(new Date(expiresAt))
}

/**
 * What a refused mint says.
 *
 * IT BRANCHES ON `code`, NEVER ON STATUS, and that distinction is the finding
 * rather than a preference (review of `#182`). `PARTNER_ALREADY_JOINED` is the
 * one refusal that says nothing: it is not a failure — the seat this couple was
 * inviting somebody into is filled, which is what they wanted — and the hook
 * has re-read the wedding, so 두 사람 모두 참여했습니다 is about to replace this
 * section. A red line beside that would be us calling good news an error.
 *
 * **BUT SILENCE ON THE WHOLE STATUS SWALLOWED TWO REAL FAILURES.** A future or
 * unrecognised 409 produced no alert, no state change and an enabled button —
 * the couple taps and the screen does not move. And a `PARTNER_ALREADY_JOINED`
 * whose refetch comes back still showing an open seat (a replica that has not
 * caught up) was silent with nothing arriving to explain it. An unrecognised
 * `code` is handled as a generic failure of its status, here as everywhere.
 *
 * WHEN THE REFETCH DOES NOT CONFIRM, THE SCREEN STILL SAYS IT. The caller
 * passes `seatWaiting`, so the one case that stays silent is the one where the
 * good news is actually on screen.
 *
 * A 404 IS NOT TOLD APART either: no such wedding, not the caller's, and
 * deleted are one answer on the server, deliberately. A 401 produces nothing —
 * the login screen is already replacing this one (`lib/queryClient.ts`).
 */
function failureMessage(error: unknown, seatWaiting: boolean): string | null {
  if (!(error instanceof ApiError)) return GENERIC_FAILURE
  if (error.status === 401) return null
  if (error.code === 'PARTNER_ALREADY_JOINED') {
    // Silent only when the couple can SEE why. Otherwise the seat still reads
    // as open and nothing on screen accounts for the tap.
    return seatWaiting
      ? '초대할 자리가 남아 있지 않습니다. 잠시 후 다시 확인해 주세요.'
      : null
  }
  return GENERIC_FAILURE
}

const GENERIC_FAILURE = '초대 링크를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
