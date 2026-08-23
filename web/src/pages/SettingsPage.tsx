import { Link, Navigate } from 'react-router'
import { buttonClassName } from '../components/Button'
import { PartnerInvite } from '../components/PartnerInvite'
import { Section, SubScreen } from '../components/SubScreen'
import { WeddingInfoForm } from '../components/WeddingInfoForm'
import { useHeadcount } from '../hooks/useHeadcount'
import { useWeddings, type Wedding } from '../hooks/useWeddings'
import { createWeddingPath, ledgerPath, myPagePath } from '../lib/routes'

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
 * IT IS A LIST OF SECTIONS, and there are three: 웨딩 정보, `#9`'s 파트너 초대,
 * and the door to 마이페이지. Each attaches as a sibling `<Section>` — that is
 * the whole extension point, and nothing about the shell is specific to what is
 * inside it, which is why the shell moved to `components/SubScreen` the moment
 * a second screen wanted the same one.
 *
 * THE WEDDING'S INFORMATION, NOT THE ACCOUNT'S, and the two do not share a
 * screen: 마이페이지 (`#159`) is the person — their name, their sign-out — while
 * this is what their wedding has. What they share is the trip, and this is
 * where it starts.
 *
 * NO 로그아웃 HERE, because 설정 is passed through rather than inhabited: its
 * exit is 원장, one tap away at the top. Which screens do carry it, and on what
 * criterion, is `components/LogoutButton`'s own doc and is not restated here.
 *
 * THAT REFUSAL IS WHY 마이페이지 EXISTS. When 로그아웃 left 원장's pinned header
 * (`#195`), this screen was the obvious landing spot and this comment turned it
 * down — so it did not shift one screen sideways: a screen a person can be
 * parked on was made, and it went there. 내 계정 below is the door
 * (notes/2026-08-22-decision-logout-leaves-the-ledger.md).
 */
export function SettingsPage() {
  return (
    <SubScreen back={{ label: '하객 명부', to: ledgerPath }} title="설정">
      <Section title="결혼식 정보">
        <WeddingInfo />
      </Section>
      {/* `#9` 파트너 초대 — the sibling this shell was built for. It reads the
          same `GET /weddings` 웨딩 정보 does, from the same cache, and decides
          for itself whether there is a seat left to invite anybody into. */}
      <Section title="파트너 초대">
        <PartnerInvite />
      </Section>
      {/* 마이페이지'S ENTRY POINT, AND ITS ONLY ONE. It is not in the ledger
          header: three controls beside the couple's own names ellipsised their
          names on a phone, which is what `#174` split the header over, and a
          screen visited about once a session does not earn a place there. Two
          taps from 원장 is the depth it is worth
          (notes/2026-08-22-decision-logout-leaves-the-ledger.md). */}
      <Section title="내 계정">
        <Link className={buttonClassName('secondary')} to={myPagePath}>
          마이페이지
        </Link>
      </Section>
    </SubScreen>
  )
}

/**
 * 결혼식 이름과 예식일과 보증인원, once both reads have answered.
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
      // 결혼식 이름 rides on `WeddingResponse` rather than on the headcount, and
      // `null` is the ordinary state of a wedding nobody has named.
      weddingName={wedding.weddingName ?? null}
    />
  )
}

function Loading() {
  return (
    <p className="text-body leading-body text-ink-muted">
      결혼식 정보를 불러오는 중입니다
    </p>
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
      <p className="text-body leading-body text-ink">결혼식 정보를 불러오지 못했습니다</p>
      <p className="text-body leading-body text-ink-muted">
        연결을 확인하고 다시 시도해 주세요.
      </p>
      <button className={buttonClassName('secondary')} onClick={onRetry} type="button">
        다시 시도
      </button>
    </div>
  )
}
