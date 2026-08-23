/*
 * The segmented control (design/components/parts/13-attendance-control.html,
 * the "상세 시트 안에서" variant) — a small set of answers, all visible, one
 * chosen.
 *
 * NATIVE RADIOS PAINTED AS SEGMENTS. The platform control supplies arrow-key
 * movement, the group's label, and "which one is chosen" to a screen reader,
 * none of which a row of styled buttons has — and the cells sit at the 44px tap
 * floor because this is filled with a thumb, on the screen a couple types their
 * entire ledger into.
 *
 * IT IS 웨딩 만들기's OWN CONTROL, GENERALISED. That screen's `SideChoice` was
 * this, for one question; 하객 추가 needed the same shape twice more (측, 참석
 * 여부) and the duplicate was folded back in here rather than left to drift.
 * Two components painting 측 is two that can disagree about the one answer on
 * either screen that has no text to fall back on.
 *
 * THE PRESSED FILL IS PER OPTION, NOT PER CONTROL, and that is the whole reason
 * `tone` exists. 측 fills with 자적, 참석 fills with 초록, and **불참 fills with
 * the neutral** — never red. A guest who cannot come is a fact, not an error,
 * and a red answer here would make a couple read their own guest list as a list
 * of failures for the months they live on this screen (design/AGENTS.md).
 */

const TONES = {
  /** 자적 — soft fill, 자적 label, and the 2px marking edge. */
  primary:
    'has-[:checked]:border-(color:--dh-primary) has-[:checked]:border-(length:--dh-mark-w) ' +
    'has-[:checked]:bg-primary-soft has-[:checked]:text-primary',
  /** 초록원삼's green. */
  yes:
    'has-[:checked]:border-(color:--dh-att-yes-fg) has-[:checked]:border-(length:--dh-mark-w) ' +
    'has-[:checked]:bg-att-yes-bg has-[:checked]:text-att-yes-fg',
  /** Neutral, and ink rather than the muted foreground: it is the chosen answer. */
  no:
    'has-[:checked]:border-(color:--dh-line-strong) has-[:checked]:border-(length:--dh-mark-w) ' +
    'has-[:checked]:bg-att-no-bg has-[:checked]:text-ink',
} as const

export type ChoiceTone = keyof typeof TONES

export type ChoiceOption<T extends string> = {
  value: T
  label: string
  tone: ChoiceTone
}

/**
 * @param id unique on the screen; ties the group to its label and its error.
 * @param name the radio group's form name, unique on the screen.
 * @param value the chosen option, or `null` for a question nobody has answered.
 */
export function Choice<T extends string>({
  error,
  id,
  label,
  name,
  onChange,
  options,
  value,
}: {
  error?: string
  id: string
  label: string
  name: string
  onChange: (value: T) => void
  options: readonly ChoiceOption<T>[]
  value: T | null
}) {
  const labelId = `${id}-label`
  const errorId = `${id}-error`
  const invalid = error !== undefined

  return (
    /* A div with `role="radiogroup"` rather than a fieldset: the group is what
       carries `aria-invalid` and points at the error, and `aria-invalid` is a
       supported state of `radiogroup` while `group` — a fieldset's implicit
       role — does not list it. */
    <div
      aria-describedby={invalid ? errorId : undefined}
      aria-invalid={invalid ? true : undefined}
      aria-labelledby={labelId}
      className="flex flex-col gap-2"
      role="radiogroup"
    >
      <span className="text-label tracking-label text-ink-muted" id={labelId}>
        {label}
      </span>
      {/* SEPARATE CELLS WITH A GAP, not one clipped box with dividers. The
          chosen cell is marked by its own 2px 자적 edge, and an edge has to
          have four sides to be one. */}
      <div className="flex gap-2">
        {options.map((option) => (
          <label
            className={`${CELL} ${TONES[option.tone]} ${
              invalid ? 'border-danger' : 'border-line-strong'
            }`}
            key={option.value}
          >
            <input
              checked={value === option.value}
              className="sr-only"
              name={name}
              onChange={() => onChange(option.value)}
              type="radio"
              value={option.value}
            />
            {option.label}
          </label>
        ))}
      </div>
      {invalid && (
        <p className="text-meta leading-snug text-danger" id={errorId}>
          {error}
        </p>
      )}
    </div>
  )
}

/*
 * The focus ring is offset INWARD: cells sit flush against their neighbours'
 * gap, and an outline drawn outside a 44px cell overlaps the next one.
 */
const CELL =
  'flex flex-1 min-h-[var(--dh-tap-min)] cursor-pointer items-center justify-center ' +
  'rounded-control border bg-surface px-3 text-body text-ink-muted transition-colors ' +
  'duration-(--dh-dur-fast) ease-standard ' +
  'has-[:focus-visible]:outline-2 has-[:focus-visible]:-outline-offset-2 ' +
  'has-[:focus-visible]:outline-focus'
