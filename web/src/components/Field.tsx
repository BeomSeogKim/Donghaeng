import type { InputHTMLAttributes, Ref, SelectHTMLAttributes } from 'react'

/*
 * The Field part (design/components/parts/11-field.html) — label, input, error.
 *
 * Written as a component rather than as class names, which is the opposite of
 * Button and for the opposite reason: a field is three elements that have to be
 * wired to each other (`htmlFor`, `aria-describedby`, `aria-invalid`), and that
 * wiring is what gets forgotten when it is copied. Button's two call sites are
 * different elements and share only paint, so a class name was enough there.
 *
 * The text input and the select are here. The select arrived with 하객 추가,
 * which is the first screen with a fixed set of answers too long to be a
 * segmented control — the seven groups. Textarea and the search variant are
 * still waiting for a screen that needs them.
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
  /**
   * The one prop a caller reaches past the label for, and 하객 추가 is why: the
   * sheet stays open after a guest is added, so focus has to go back to the
   * name for the next one — otherwise a couple entering a list their parents
   * sent them taps the field again for every single row.
   */
  ref?: Ref<HTMLInputElement>
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

type SelectFieldProps = {
  id: string
  label: string
  error?: string
} & Omit<
  SelectHTMLAttributes<HTMLSelectElement>,
  'aria-describedby' | 'aria-invalid' | 'className' | 'id'
>

/**
 * The same field, answered from a fixed list.
 *
 * IT IS THE PLATFORM'S SELECT, not a menu of our own. Mobile is the primary
 * device and the control worth having is the one the phone already has — the
 * native picker — for the same reason 웨딩 만들기 takes the native date input.
 */
export function SelectField({ error, id, label, ...select }: SelectFieldProps) {
  const errorId = `${id}-error`
  const invalid = error !== undefined

  return (
    <div className="flex flex-col gap-2">
      <label className="text-meta font-semibold text-ink" htmlFor={id}>
        {label}
      </label>
      <select
        aria-describedby={invalid ? errorId : undefined}
        aria-invalid={invalid ? true : undefined}
        className={`${INPUT} ${invalid ? 'border-danger' : 'border-line-strong'}`}
        id={id}
        {...select}
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
