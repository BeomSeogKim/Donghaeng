import type { InputHTMLAttributes } from 'react'

/*
 * The Field part (design/components/parts/11-field.html) — label, input, error.
 *
 * Written as a component rather than as class names, which is the opposite of
 * Button and for the opposite reason: a field is three elements that have to be
 * wired to each other (`htmlFor`, `aria-describedby`, `aria-invalid`), and that
 * wiring is what gets forgotten when it is copied. Button's two call sites are
 * different elements and share only paint, so a class name was enough there.
 *
 * Only the text input is here. The part also carries select, textarea and the
 * search variant; those arrive with the screens that need them.
 *
 * THE ERROR TEXT SAYS WHAT IS WRONG AND HOW TO FIX IT — that rule lives with
 * the copy at the call site, not here. What is fixed here is that an error
 * turns the border red AND is announced, because a colour alone is not a
 * message to someone who cannot see it.
 */

type FieldProps = {
  /** Ties the label, the input and the error together. Unique on the screen. */
  id: string
  label: string
  /** The message shown under the field, or `undefined` when it is fine. */
  error?: string
} & Omit<
  InputHTMLAttributes<HTMLInputElement>,
  'aria-describedby' | 'aria-invalid' | 'className' | 'id'
>

export function Field({ error, id, label, ...input }: FieldProps) {
  const errorId = `${id}-error`
  const invalid = error !== undefined

  return (
    <div className="flex flex-col gap-2">
      <label className="text-meta font-semibold text-ink" htmlFor={id}>
        {label}
      </label>
      <input
        aria-describedby={invalid ? errorId : undefined}
        aria-invalid={invalid ? true : undefined}
        className={`${INPUT} ${invalid ? 'border-danger' : 'border-line-strong'}`}
        id={id}
        {...input}
      />
      {invalid && (
        <p className="text-meta leading-snug text-danger" id={errorId}>
          {error}
        </p>
      )}
    </div>
  )
}

// The 44px floor is the tap minimum from the token file: this is filled with a
// thumb on a phone, and on a date input the whole control is the picker.
const INPUT =
  'min-h-[var(--dh-tap-min)] w-full rounded-control border bg-surface px-3 ' +
  'text-body text-ink placeholder:text-ink-faint ' +
  'focus:border-focus focus:outline-2 focus:outline-offset-1 focus:outline-focus'
