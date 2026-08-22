import type { ReactNode } from 'react'
import { Link, Navigate } from 'react-router'
import { buttonClassName } from '../components/Button'
import { PartnerInvite } from '../components/PartnerInvite'
import { WeddingInfoForm } from '../components/WeddingInfoForm'
import { useHeadcount } from '../hooks/useHeadcount'
import { useWeddings, type Wedding } from '../hooks/useWeddings'
import { createWeddingPath, ledgerPath } from '../lib/routes'

/*
 * 설정 — the wedding's own information, and **the shell every other setting
 * joins.**
 *
 * IT IS A ROUTE, NOT A SHEET, and that is the one structural call here. 원장 is
 * home and everything frequent opens over it, but this is a trip a couple makes
 * rarely and leaves: the flow has always been drawn as 원장 ↔ 설정
 * (notes/2026-08-07-design-screens-and-flow.md). A sheet would also have to
 * hold a partner invite later, and an invite flow inside a sheet over the
 * ledger is a screen pretending to be an overlay.
 *
 * IT IS A LIST OF SECTIONS, AND `#9` ADDS THE SECOND ONE. 파트너 초대 attaches
 * as a sibling `<Section>` below 웨딩 정보 — that is the whole extension point,
 * and it is why the section frame exists for one section. Nothing about this
 * shell is specific to the form inside it.
 *
 * THE WEDDING'S INFORMATION, NOT THE ACCOUNT'S. 마이페이지 (`#159`) is the
 * account — the person, their login, their sign-out — and whether the two ever
 * share a screen is that issue's call, not a question this one answers.
 *
 * NO 로그아웃 HERE. It sits on the screens a signed-in person can be *parked*
 * on with no other exit (원장, 웨딩 만들기); this screen's exit is 원장, one tap
 * away at the top, and a second sign-out button is a second place to press for
 * one action.
 */
export function SettingsPage() {
  return (
    <Frame>
      <Section title="웨딩 정보">
        <WeddingInfo />
      </Section>
      {/* `#9` 파트너 초대 — the sibling this shell was built for. It reads the
          same `GET /weddings` 웨딩 정보 does, from the same cache, and decides
          for itself whether there is a seat left to invite anybody into. */}
      <Section title="파트너 초대">
        <PartnerInvite />
      </Section>
    </Frame>
  )
}

/**
 * 예식일과 보증인원, once both reads have answered.
 *
 * THE FORM IS NOT RENDERED UNTIL IT CAN BE PREFILLED, and on this screen that
 * is a data rule rather than a loading nicety: an empty 보증인원 field does not
 * mean "unknown", it means **clear the venue's number**. A form built over a
 * read that failed would offer the couple a blank box that saves as a deletion.
 *
 * 보증인원 IS PREFILLED FROM `GET /weddings/{weddingId}/headcount`, WHICH IS THE
 * ONLY PLACE IT IS PUBLISHED. `WeddingResponse` does not carry it and is not
 * going to — one response may not spell one number twice — and the spec was
 * corrected on 2026-08-22 to say so, having predicted the opposite
 * (docs/api-spec.md § GET /weddings/{weddingId}).
 *
 * THE WEDDING COMES FROM `GET /weddings` AND ITS ID IS NOT IN THE URL, exactly
 * as on 원장: a person belongs to one wedding, the list is newest first and that
 * order is contract, so a reload lands on the same wedding.
 */
function WeddingInfo() {
  const weddings = useWeddings()

  if (weddings.isPending) return <Loading />
  if (weddings.isError) return <Failure onRetry={() => void weddings.refetch()} />

  const wedding = weddings.data[0]
  // An empty list is the ordinary answer for a person with no wedding — 최초
  // 1회, and also what being removed from a partner's wedding looks like. There
  // is nothing here to configure until one exists.
  if (wedding === undefined) return <Navigate replace to={createWeddingPath} />

  return <Prefilled wedding={wedding} />
}

function Prefilled({ wedding }: { wedding: Wedding }) {
  const headcount = useHeadcount(wedding.id)

  if (headcount.isPending) return <Loading />
  if (headcount.isError) return <Failure onRetry={() => void headcount.refetch()} />

  return (
    <WeddingInfoForm
      // 보증인원 is ABSENT, never `null`, until a couple has one, and cleared
      // reads exactly like never-set — the two are one state
      // (docs/api-spec.md § GET /weddings/{weddingId}/headcount).
      guaranteedHeadcount={headcount.data.guaranteedHeadcount ?? null}
      weddingDate={wedding.weddingDate}
      weddingId={wedding.id}
    />
  )
}

/**
 * The screen around the sections: a title, and the way back to 원장.
 *
 * `Frame`, NOT `Screen`: `components/Screen` is the centered single-column shell
 * the login and 웨딩 만들기 screens wear, and it means that specific thing. 원장
 * named its own frame the same way for the same reason.
 *
 * THE WAY BACK IS ON THE SCREEN, not left to the browser's Back button — this
 * is the only route in the app a couple arrives at by choice and has to leave
 * again, and on a phone installed to the home screen there is no Back button at
 * all.
 */
function Frame({ children }: { children: ReactNode }) {
  return (
    <main className="min-h-[100dvh] bg-ground text-ink">
      <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-line bg-ground px-4 py-3 md:px-6">
        <Link className={buttonClassName('secondary')} to={ledgerPath}>
          <span aria-hidden="true" className="mr-1">
            ←
          </span>
          원장
        </Link>
        {/* One of RIDIBatang's three places: the headcount, screen titles, the
            brand mark. */}
        <h1 className="font-display text-title leading-tight tracking-display">설정</h1>
      </header>

      <div className="flex flex-col gap-6 py-6">{children}</div>
    </main>
  )
}

/**
 * One setting, framed.
 *
 * Flush and hairline-separated on a phone, a bordered block on a laptop — the
 * ledger's rule, for the same reason: a card per section costs vertical rhythm
 * and buys nothing on a screen that is a list of two things.
 */
function Section({ children, title }: { children: ReactNode; title: string }) {
  return (
    <section
      aria-label={title}
      className="border-y border-line bg-surface px-4 py-5 md:mx-6 md:max-w-104 md:border md:px-6"
    >
      <h2 className="text-lead font-semibold leading-snug">{title}</h2>
      <div className="mt-4">{children}</div>
    </section>
  )
}

function Loading() {
  return (
    <p className="text-body leading-body text-ink-muted">웨딩 정보를 불러오는 중입니다</p>
  )
}

/**
 * One failure, one message, one way out — and no attempt to tell 404 apart from
 * anything else, because the server deliberately does not
 * (docs/api-spec.md § GET /weddings/{weddingId}). A 401 never gets here: the
 * client writes the session to `null` on any 401 and the login screen replaces
 * this one (lib/queryClient.ts).
 */
function Failure({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="flex flex-col items-start gap-3">
      <p className="text-body leading-body text-ink">웨딩 정보를 불러오지 못했습니다</p>
      <p className="text-body leading-body text-ink-muted">
        연결을 확인하고 다시 시도해 주세요.
      </p>
      <button className={buttonClassName('secondary')} onClick={onRetry} type="button">
        다시 시도
      </button>
    </div>
  )
}
