import { GROUP_LABELS, type Guest } from '../hooks/useGuests'
import { Tag } from './Tag'

/*
 * One row of 하객 명부 (design/components/parts/14-guest-row.html).
 *
 * NO HORIZONTAL ROW RULES, as of 2026-08-23. What separates two rows is 44px of
 * height and the 17px/500 name against the 13px/400 apparatus; what rules
 * instead is a VERTICAL 괘선 between column groups — a Joseon ledger's ruling,
 * and the first form in this system that is Korean rather than named Korean
 * (notes/2026-08-23-decision-the-form-language.md). Flush and never a card: a
 * per-row card costs about 8px of vertical rhythm each, and at 400 rows that is
 * what makes a ledger slower to scan than the spreadsheet it replaces.
 *
 * THE COLUMN GAPS ARE UNEVEN — 32 · 16 · 24, and they are tokens. Even gaps are
 * a spreadsheet.
 *
 * ONE DOM, TWO LAYOUTS. 60px on a phone with the apparatus on a second line,
 * 44px on a laptop with it in columns — and `md:contents` is what lets the same
 * elements be either, rather than rendering both and shipping every guest's
 * name to the page twice. The PC *table* proper — the contact column and the
 * aggregation rail — is what would earn a second structure, and neither exists
 * yet (`#17`, `#18`).
 *
 * NO DISPLAY FACE ANYWHERE IN HERE. Korean serif at 15px across 400 rows is
 * measurably slower to scan, and the list is the one place that would cost.
 *
 * THE COLUMN WIDTHS LIVE IN THIS FILE AND SO DOES THE HEADER, because a header
 * that sets its own widths is a header that drifts one column at a time.
 */

const SIDE = { GROOM: '신랑', BRIDE: '신부' } as const

/** 이름 · 측 · 그룹 · 라벨 · 인원 · 참석 — the widths and the gaps, once. */
const COL = {
  /** The disclosure's slot. Empty until a party can fold (`#213`). */
  caret: 'w-5 shrink-0',
  name: 'w-full md:w-50 md:shrink-0',
  side: 'md:w-13 md:shrink-0 md:ms-(--dh-col-gap-name)',
  group: 'md:w-21 md:shrink-0 md:ms-(--dh-col-gap-apparatus)',
  label: 'md:flex-1 md:min-w-0 md:ms-(--dh-col-gap-apparatus)',
  size: 'w-9 md:w-12 shrink-0 text-right ms-auto md:ms-(--dh-col-gap-figure)',
  attendance: 'w-(--dh-col-w-att) shrink-0 text-right ms-(--dh-col-gap-figure)',
  /** The 괘선 itself — self-stretching so it rules the full row height. */
  rule: 'hidden md:block self-stretch w-(--dh-rule-column) bg-line shrink-0',
} as const

export function GuestRow({ guest }: { guest: Guest }) {
  const label = guest.groupLabel ?? ''

  return (
    <li className={ROW}>
      <span aria-hidden="true" className={COL.caret} />

      <div className="flex min-w-0 flex-1 flex-col justify-center md:contents">
        {/* The name is the row's content, not a heading: 400 headings is a
            worse outline than none. There is nothing semantic to hang the test
            hook on until the row opens the detail sheet (`#12`). */}
        <p
          className={`${COL.name} truncate text-lead font-medium leading-snug tracking-name`}
          data-testid="guest-name"
        >
          {guest.name}
        </p>

        {/* On a phone this is one apparatus line under the name; on a laptop
            `md:contents` dissolves it and its children become the row's own
            columns. The separators are what a line needs and a column does
            not. */}
        <p className="flex min-w-0 items-center gap-1 text-meta leading-snug md:contents">
          <span className={COL.side}>
            <Tag variant="side">{SIDE[guest.side]}</Tag>
          </span>
          <Dot />
          <span className={COL.group}>
            <Tag variant="group">{GROUP_LABELS[guest.groupCategory]}</Tag>
          </span>
          {/* THE 괘선 AND THE LABEL COLUMN ARE ALWAYS DRAWN, even for a guest
              with no label. A rule that appears on some rows and not others is
              not a ruling, it is a decoration that happens to line up. The dot
              is the phone's separator and belongs to the text, so it is the one
              thing here that is conditional. */}
          {label !== '' && <Dot />}
          <span
            aria-hidden="true"
            className={`${COL.rule} ms-(--dh-col-gap-apparatus)`}
          />
          <span className={COL.label}>
            {label !== '' && <Tag variant="free">{label}</Tag>}
          </span>
        </p>
      </div>

      <span aria-hidden="true" className={`${COL.rule} ms-(--dh-col-gap-figure)`} />

      {/* 인원 — a figure under a column heading, so the unit is read by the
          heading and spoken by the screen reader rather than printed 400 times.
          Every digit that can change in place is tabular. */}
      <span className={`${COL.size} text-lead tabular-nums`}>
        {guest.expectedPartySize}
        <span className="sr-only">명</span>
      </span>

      {/*
       * 참석 IS A WORD IN A 48px COLUMN, NOT A BADGE. The capsule this replaced
       * was 62% of the row height repeated 400 times. A READOUT, NOT YET A
       * CONTROL: tapping it is `#13`, and until that endpoint exists a button
       * here would be a control that does nothing. 불참 is neutral and never red
       * — a guest who cannot come is a fact, not an error.
       */}
      <span
        className={`${COL.attendance} text-meta leading-snug tabular-nums ${
          guest.expectedAttending ? 'font-semibold text-att-yes-fg' : 'text-ink-muted'
        }`}
      >
        {guest.expectedAttending ? '참석' : '불참'}
      </span>
    </li>
  )
}

/**
 * The column headings, the one horizontal rule the ledger still has.
 *
 * IT IS EXPORTED FROM HERE, beside the widths it heads. Two files agreeing on
 * six column widths is two files that stop agreeing at the next change to
 * either.
 */
export function LedgerHeader() {
  return (
    <div
      aria-hidden="true"
      /* The same 2px transparent leading edge every row carries, so the
         headings sit over their columns rather than 2px to the left of them.
         A row's edge is the hover mark; here it is spacing and nothing else. */
      className="flex h-(--dh-row-h-head) shrink-0 items-center border-s-(length:--dh-mark-w) border-b border-transparent border-b-line-strong px-4 text-label tracking-label text-ink-muted md:px-6"
    >
      <span className={COL.caret} />
      <span className={`${COL.name} hidden md:block`}>이름</span>
      <span className="md:hidden flex-1">이름</span>
      <span className={`${COL.side} hidden md:block`}>측</span>
      <span className={`${COL.group} hidden md:block`}>그룹</span>
      <span className={`${COL.rule} ms-(--dh-col-gap-apparatus)`} />
      <span className={`${COL.label} hidden md:block`}>그룹 라벨</span>
      <span className={`${COL.rule} ms-(--dh-col-gap-figure)`} />
      <span className={COL.size}>인원</span>
      <span className={COL.attendance}>참석</span>
    </div>
  )
}

/*
 * THE TAP TARGET IS THE WHOLE ROW — that is what makes the badge unnecessary
 * rather than merely smaller — so the hover mark belongs to the row and is a
 * 2px 자적 edge on its leading side rather than a fill. A filled row stops the
 * eye on every row of a 400-row scan, which is the one thing this list may not
 * do.
 */
const ROW =
  'group flex items-center min-h-(--dh-row-h-mobile) px-4 md:min-h-0 ' +
  'md:h-(--dh-row-h-desktop) md:px-6 ' +
  'border-b-(length:--dh-rule-row) border-s-(length:--dh-mark-w) border-transparent ' +
  'transition-colors duration-(--dh-dur-fast) ease-standard ' +
  'hover:border-s-(color:--dh-primary)'

/** The separator a phone's apparatus line needs and a laptop's columns do not. */
function Dot() {
  return (
    <span aria-hidden="true" className="text-ink-faint md:hidden">
      ·
    </span>
  )
}
