import { useId, useState } from 'react'
import { GROUP_LABELS, type Guest, type GuestParty } from '../hooks/useGuests'
import { Tag } from './Tag'

/*
 * One row of 하객 명부 (design/components/parts/14-guest-row.html).
 *
 * ONE ROW IS ONE PARTY, NOT ONE PERSON (`#213`,
 * notes/2026-08-23-decision-companions-become-guests.md). A party of two or
 * more carries a disclosure; expanding shows `members`, each holding its own
 * 참석. The people ride along with the party in the ledger's own response, so
 * expanding is a client-side disclosure and never a request — on the screen
 * whose whole loop is scan-tap-watch, a round trip on a tap is the one thing it
 * cannot spend.
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
  /** The disclosure's slot — empty on a party of one, which cannot fold. */
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

export function GuestRow({ party }: { party: GuestParty }) {
  /*
   * WHETHER THIS ROW IS OPEN IS THIS ROW'S OWN STATE AND NOBODY ELSE'S, so it
   * stays on the bottom rung — `useState`, here
   * (notes/2026-08-08-decision-frontend-architecture.md). The row is keyed on
   * the party's id in the list, so it survives a refetch and a re-sort.
   */
  const [expanded, setExpanded] = useState(false)
  const membersId = useId()

  /*
   * THE APPARATUS DESCRIBES THE PERSON THE ROW LEADS WITH, which is the head
   * whenever the head is here: `members` is in entry order, head first when it
   * is here (docs/api-spec.md § GET /weddings/{weddingId}/guests). Under a
   * filter that excluded the head — 대표자는 불참인데 동반은 참석인 팀 under
   * the 참석 chip — the row is still NAMED after the head, because that is how
   * the couple recognises it, while 측 · 그룹 · 라벨 describe somebody who is
   * actually on the row rather than somebody the filter removed.
   *
   * A party always has at least one member: it appears in the response only
   * because one of its people matched.
   */
  const lead = party.members[0]
  const label = lead.groupLabel ?? ''

  /** A party of one is a person, and a person does not fold. */
  const folds = party.size > 1

  return (
    <li>
      <div className={ROW}>
        {folds ? (
          <Caret
            controls={membersId}
            expanded={expanded}
            name={party.name}
            onToggle={() => setExpanded((open) => !open)}
          />
        ) : (
          <span aria-hidden="true" className={COL.caret} />
        )}

        <div className="flex min-w-0 flex-1 flex-col justify-center md:contents">
          {/* The name is the row's content, not a heading: 400 headings is a
              worse outline than none. There is nothing semantic to hang the test
              hook on until the row opens the detail sheet (`#12`). */}
          <p
            className={`${COL.name} truncate text-lead font-medium leading-snug tracking-name`}
            data-testid="guest-name"
          >
            {party.name}
          </p>

          {/* On a phone this is one apparatus line under the name; on a laptop
              `md:contents` dissolves it and its children become the row's own
              columns. The separators are what a line needs and a column does
              not. */}
          <p className="flex min-w-0 items-center gap-1 text-meta leading-snug md:contents">
            <span className={COL.side}>
              <Tag variant="side">{SIDE[lead.side]}</Tag>
            </span>
            <Dot />
            <span className={COL.group}>
              <Tag variant="group">{GROUP_LABELS[lead.groupCategory]}</Tag>
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
            It is the server's `size`, which under a filter is how many of this
            party matched. Every digit that can change in place is tabular. */}
        <span className={`${COL.size} text-lead tabular-nums`}>
          {party.size}
          <span className="sr-only">명</span>
        </span>

        <Attendance
          controls={membersId}
          expanded={expanded}
          onExpand={() => setExpanded(true)}
          party={party}
        />
      </div>

      {folds && expanded && (
        /*
         * ONE STEP DOWN FROM THE SLAB, and that is the whole of how a member
         * row says it belongs to the line above it: no card, no indent rule,
         * no second border. 참석 stays in its own column, so the expansion
         * reads down the same edge the collapsed rows do.
         */
        <ul className="bg-ground" id={membersId}>
          {party.members.map((member) => (
            <MemberRow key={member.id} member={member} />
          ))}
        </ul>
      )}
    </li>
  )
}

/**
 * 참석 — and there are three readings, the third being the point.
 *
 * A MIXED PARTY HAS NO ATTENDANCE, so the column states the count it does know
 * — `3 / 4` — and pressing it EXPANDS THE ROW rather than picking a side. That
 * is 애매한 것은 추측하지 않는다 applied to a control instead of to an import
 * (notes/2026-08-23-decision-companions-become-guests.md): the couple heard
 * something about one person in that party, and choosing 참석 or 불참 for all
 * four of them on their behalf would be the guess.
 *
 * 참석 AND 불참 ARE STILL A READOUT AND NOT YET A CONTROL — tapping them is
 * `#13`, and until that endpoint exists a button there would be a control that
 * does nothing. The mixed reading is a button today because it already has
 * something to do. 불참 is neutral and never red: a guest who cannot come is a
 * fact, not an error.
 *
 * IT IS A WORD IN A 48px COLUMN, NOT A BADGE. The capsule this replaced was 62%
 * of the row height repeated 400 times.
 */
function Attendance({
  controls,
  expanded,
  onExpand,
  party,
}: {
  controls: string
  expanded: boolean
  onExpand: () => void
  party: GuestParty
}) {
  const className = `${COL.attendance} text-meta leading-snug tabular-nums`

  if (party.attendingCount === party.size)
    return <span className={`${className} font-semibold text-att-yes-fg`}>참석</span>

  if (party.attendingCount === 0)
    return <span className={`${className} text-ink-muted`}>불참</span>

  return (
    <button
      aria-controls={controls}
      aria-expanded={expanded}
      // Full row height, so the 48px column is a 48px-by-44px target rather
      // than a 48px-by-19px line of text.
      className={`${className} flex items-center justify-end self-stretch font-semibold text-ink`}
      onClick={onExpand}
      type="button"
    >
      <span aria-hidden="true">
        {party.attendingCount} / {party.size}
      </span>
      <span className="sr-only">
        {party.size}명 중 {party.attendingCount}명 참석, 펼치기
      </span>
    </button>
  )
}

/**
 * One person inside an expanded party.
 *
 * A COMPANION'S NAME IS GIVEN ONCE AND NEVER REGENERATED — `박영희 동반 1` is
 * a stored name, not a caption derived from the head's. Renaming the head later
 * does not rename them, which is why nothing here rebuilds the string.
 *
 * The head reads in ink and a companion in the muted voice, so the person the
 * row is named after is findable inside their own expansion. There is no second
 * cue: `companionOf` says the same thing the name already says.
 */
function MemberRow({ member }: { member: Guest }) {
  return (
    <li className={MEMBER_ROW}>
      <span aria-hidden="true" className={COL.caret} />
      <p
        className={`min-w-0 flex-1 truncate text-body leading-snug ${
          (member.companionOf ?? null) === null ? 'text-ink' : 'text-ink-muted'
        }`}
      >
        {member.name}
      </p>
      <span
        className={`${COL.attendance} text-meta leading-snug ${
          member.expectedAttending ? 'font-semibold text-att-yes-fg' : 'text-ink-muted'
        }`}
      >
        {member.expectedAttending ? '참석' : '불참'}
      </span>
    </li>
  )
}

/**
 * The disclosure — a chevron that turns, and nothing else.
 *
 * IT NAMES THE PARTY RATHER THAN SAYING "펼치기", because 400 buttons all called
 * 펼치기 is a list nobody can navigate by name. `aria-expanded` carries the
 * state, so the label does not have to change under the couple's thumb.
 *
 * THE COLUMN IS 20px AND THE TAP TARGET IS NOT. A caret drawn at the size it is
 * pressed at would be a 44px column of chevron on a screen whose whole argument
 * is density, so the glyph keeps the column and the hit box is grown past it —
 * full row height, and 12px either side of the 20px column. The leading 12px
 * falls in the row's own gutter; the trailing 12px reaches into the name, which
 * costs nothing while the name is not a control and is what a nested control
 * inside a tappable row needs anyway when `#12` makes the row one.
 */
function Caret({
  controls,
  expanded,
  name,
  onToggle,
}: {
  controls: string
  expanded: boolean
  name: string
  onToggle: () => void
}) {
  return (
    <button
      aria-controls={controls}
      aria-expanded={expanded}
      aria-label={`${name} 동반 인원`}
      className={`${COL.caret} relative flex self-stretch items-center justify-center text-ink-faint before:absolute before:inset-y-0 before:-inset-x-3`}
      onClick={onToggle}
      type="button"
    >
      <svg
        aria-hidden="true"
        className={`size-(--dh-text-meta) transition-transform duration-(--dh-dur-fast) ease-standard ${
          expanded ? 'rotate-90' : ''
        }`}
        fill="none"
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="2"
        viewBox="0 0 24 24"
      >
        <path d="M9 18l6-6-6-6" />
      </svg>
    </button>
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

/*
 * A member sits one step under the row that carries it — 38px on a laptop
 * against the row's 44px — and it wears the same transparent 2px leading edge
 * so its 이름 starts where the party's does. On a phone the tap floor
 * overrules the 38px, because a member's 참석 becomes a control with `#13`.
 */
const MEMBER_ROW =
  'flex items-center min-h-(--dh-tap-min) px-4 md:min-h-0 ' +
  'md:h-(--dh-row-h-member) md:px-6 ' +
  'border-s-(length:--dh-mark-w) border-transparent'

/** The separator a phone's apparatus line needs and a laptop's columns do not. */
function Dot() {
  return (
    <span aria-hidden="true" className="text-ink-faint md:hidden">
      ·
    </span>
  )
}
