import { GROUP_LABELS, type Guest } from '../hooks/useGuests'
import { Tag } from './Tag'

/*
 * One row of 원장 (design/components/parts/14-guest-row.html).
 *
 * FLUSH AND HAIRLINE-SEPARATED, NEVER A CARD. A per-row card costs about 8px of
 * vertical rhythm each, and at 400 rows that is what makes a ledger slower to
 * scan than the spreadsheet it replaces (design/AGENTS.md). The hairline and the
 * surface belong to the list, not to the row.
 *
 * 60px on mobile, 44px on desktop — row height is a product decision, and both
 * numbers are tokens. The two are one row here rather than two components: the
 * name and its metadata stack on a phone and sit on one line on a laptop, which
 * is what the two heights actually mean. The PC *table* — the contact column and
 * the aggregation rail beside it — is what earns a second structure, and neither
 * exists yet (`#17`, `#18`); building the table before them would be a wide
 * phone screen with more code.
 *
 * NO DISPLAY FACE ANYWHERE IN HERE. Korean serif at 15px across 400 rows is
 * measurably slower to scan, and the list is the one place that would cost.
 */

const SIDE = { GROOM: '신랑', BRIDE: '신부' } as const

export function GuestRow({ guest }: { guest: Guest }) {
  const label = guest.groupLabel ?? ''

  return (
    <li className="flex min-h-[var(--dh-row-h-mobile)] items-center justify-between gap-3 px-4 py-2 md:min-h-[var(--dh-row-h-desktop)] md:px-6 md:py-0">
      <div className="flex min-w-0 flex-col md:flex-row md:items-baseline md:gap-3">
        {/* The name is the row's content, not a heading: 400 headings is a
            worse outline than none. There is nothing semantic to hang the test
            hook on until the row opens the detail sheet (`#12`). */}
        <p
          className="truncate text-lead font-medium leading-snug"
          data-testid="guest-name"
        >
          {guest.name}
        </p>
        <p className="flex min-w-0 items-center gap-2 text-meta leading-snug text-ink-muted">
          <Tag variant="side">{SIDE[guest.side]}</Tag>
          <Tag variant="group">{GROUP_LABELS[guest.groupCategory]}</Tag>
          {label !== '' && <Tag variant="free">{label}</Tag>}
          {/* One text node, so the count and its unit never wrap apart. Every
              digit that can change in place is tabular. */}
          <span className="whitespace-nowrap tabular-nums">{`${guest.expectedPartySize}명`}</span>
        </p>
      </div>

      {/* A READOUT, NOT YET A CONTROL. Tapping it is `#13`, and until that
          endpoint exists a button here would be a control that does nothing.
          불참 is neutral and never red — a guest who cannot come is a fact, not
          an error. */}
      <span
        className={`inline-flex min-h-9 min-w-14 items-center justify-center rounded-chip px-3 text-body font-semibold leading-none ${
          guest.expectedAttending
            ? 'bg-att-yes-bg text-att-yes-fg'
            : 'bg-att-no-bg text-att-no-fg'
        }`}
      >
        {guest.expectedAttending ? '참석' : '불참'}
      </span>
    </li>
  )
}
