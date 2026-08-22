import { type FormEvent, useState } from 'react'
import { type UpdateWeddingRequest, useUpdateWedding } from '../hooks/useUpdateWedding'
import { ApiError } from '../lib/api'
import { buttonClassName } from './Button'
import { Field } from './Field'

/*
 * 웨딩 정보 — 예식일과 보증인원, and the only place either is edited.
 *
 * 보증인원 IS THE VENUE'S NUMBER, NEVER OURS. Nothing on this screen recommends
 * one, hints at ours, or subtracts anything: no 식대 인원 beside the box, no
 * 여유/초과, no meter. The comparison belongs on 원장, where the couple reads it
 * — putting our count next to the field they type the venue's into would make
 * it a suggestion (root AGENTS.md).
 *
 * 미설정 IS A NORMAL STATE AND THIS SCREEN IS WHERE THE METER IS SWITCHED ON.
 * Couples sign up before they book a venue, so 웨딩 만들기 does not ask for the
 * number and 원장 draws no comparison until it exists
 * (notes/2026-08-07-design-screens-and-flow.md). A couple can also arrive back
 * at 미설정 — a contract that fell through, a venue changed, a number typed into
 * the wrong box — so emptying the field is a real answer rather than an
 * unfinished form.
 *
 * WHAT IS SENT IS WHAT MOVED, AND NOTHING ELSE. This is the product's first
 * partial update: a member left out is not written at all, which is what lets
 * one partner change 예식일 while the other changes 보증인원 without either of
 * them blind-writing the other's field — and `wedding` has no audit trail to
 * recover an overwrite from (notes/2026-08-22-decision-partial-update-shape.md).
 */
export function WeddingInfoForm({
  guaranteedHeadcount,
  weddingDate,
  weddingId,
}: {
  /** As the headcount endpoint publishes it — `null` when the couple has none. */
  guaranteedHeadcount: number | null
  weddingDate: string
  weddingId: number
}) {
  const update = useUpdateWedding(weddingId)

  /*
   * WHAT THE SERVER HOLDS, AND THE ONLY THING "CHANGED" CAN BE MEASURED
   * AGAINST. It is state rather than the props themselves, because the props go
   * on moving: `refetchOnWindowFocus` is deliberate, so a partner's edit can
   * land under a form that is being typed into. Diffing against the live value
   * would then call an untouched field "changed" and send the couple's stale
   * copy back over their partner's edit — precisely the blind write a partial
   * update exists to prevent. It advances on a successful save, from that
   * response, so a second press has nothing left to send.
   */
  const [saved, setSaved] = useState<Saved>({ weddingDate, guaranteedHeadcount })
  const [values, setValues] = useState<FormValues>({
    weddingDate,
    guaranteedHeadcount: guaranteedHeadcount === null ? '' : String(guaranteedHeadcount),
  })

  /*
   * Errors are derived, not stored: they appear on the first save and then
   * disappear as each field is fixed, with no second copy of the form's state
   * to keep in step.
   */
  const [submitted, setSubmitted] = useState(false)
  const sending = trimmed(values)
  const errors = submitted ? validate(sending) : {}
  const changes = changed(sending, saved)
  /** Whether what is on screen still differs from what the server holds. */
  const edited = Object.keys(changes).length > 0

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    // A submit is a user action, so it is handled here — never in an Effect
    // watching a flag.
    event.preventDefault()
    setSubmitted(true)

    // Serialising mutations DELAYS a second press; it never refuses one, so this
    // guard is the only thing that stops a double press becoming a second write
    // (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
    if (update.isPending) return
    if (Object.keys(validate(sending)).length > 0) return

    update.mutate(changes, {
      onSuccess: (response) =>
        setSaved({
          weddingDate: response.wedding.weddingDate,
          // Absent, never null, once it is cleared — the two are one state.
          guaranteedHeadcount: response.headcount.guaranteedHeadcount ?? null,
        }),
    })
  }

  const failure = update.isError ? failureMessage(update.error) : null

  return (
    <form className="flex flex-col gap-5" onSubmit={handleSubmit}>
      {/* A native `type="date"`: mobile is the primary device, so the control
          worth having is the one the phone already has — and its value is
          `YYYY-MM-DD`, which is exactly what the API wants. */}
      <Field
        error={errors.weddingDate}
        id="wedding-date"
        label="예식일"
        onChange={(event) => setValues({ ...values, weddingDate: event.target.value })}
        type="date"
        value={values.weddingDate}
      />

      <div className="flex flex-col gap-2">
        {/*
         * NOT A `type="number"`, AND THAT IS THE DECISION IN THIS FILE. A number
         * input hands back `""` for anything it cannot parse, and on this field
         * an empty value does not mean "unknown" — it means CLEAR THE VENUE'S
         * NUMBER. So a mistyped 보증인원 would read as a deliberate blanking. As
         * text with a numeric keypad, what was typed stays on screen and is
         * named instead.
         */}
        <Field
          autoComplete="off"
          error={errors.guaranteedHeadcount}
          id="guaranteed-headcount"
          inputMode="numeric"
          label="보증인원"
          onChange={(event) =>
            setValues({ ...values, guaranteedHeadcount: event.target.value })
          }
          type="text"
          value={values.guaranteedHeadcount}
        />
        <p className="text-body leading-body text-ink-muted">
          예식장과 계약한 인원입니다. 아직 정하지 않았다면 비워 두세요.
        </p>
      </div>

      {/*
       * WHAT THE WRITE DID, and no more than that: the recomputed 인원수 is in
       * this response and goes to 원장, which is the screen that reads numbers.
       * Repeating it here would put our count beside the venue's.
       *
       * It withdraws the moment the couple types again, because from then on it
       * describes something other than what is on the screen.
       */}
      {update.isSuccess && !edited && (
        <p className="text-body leading-body text-att-yes-fg" role="status">
          저장했습니다.
        </p>
      )}

      {failure !== null && (
        // Announced, because it appears away from the button that was just
        // pressed and nothing else on the screen changes to say so.
        <p className="text-body leading-body text-danger" role="alert">
          {failure}
        </p>
      )}

      <button
        className={`${buttonClassName('primary')} w-full`}
        disabled={update.isPending}
        type="submit"
      >
        저장
      </button>
    </form>
  )
}

/** What the server holds, as far as this form has been told. */
type Saved = {
  weddingDate: string
  guaranteedHeadcount: number | null
}

/**
 * The form, which is not the request: 보증인원 is held as the string the input
 * holds, because `''` is a state the request has no member for — it is what
 * `null` is sent for, and `null` is the one spelling of "clear".
 */
type FormValues = {
  weddingDate: string
  guaranteedHeadcount: string
}

/** What is sent, which is not quite what was typed. */
function trimmed(values: FormValues): FormValues {
  return { ...values, guaranteedHeadcount: values.guaranteedHeadcount.trim() }
}

/**
 * The body: **only the members that moved.**
 *
 * A BLANK 보증인원 IS SENT AS `null` AND NEVER AS `""` OR AS `undefined`, which
 * are the two ways this goes wrong. `""` is what `JSON.stringify` makes of a
 * blanked input and is a 400 `MALFORMED_REQUEST_BODY` — it answered 200 and
 * silently erased the number until `#173`'s review; `undefined` is dropped by
 * `JSON.stringify` altogether, which turns "지운다" into "그대로 둔다" and
 * answers 200 having written nothing. So the member is spread in only when it
 * moved, and it carries `null` when it moved to empty.
 *
 * `weddingDate` HAS NO CLEARED FORM: a blank one is refused by `validate` below
 * rather than sent, because a wedding always has a date and `null` is a 400.
 *
 * An empty body is legal — 200, nothing written, `updated_at` untouched — so a
 * form saved with nothing edited needs no special case (docs/api-spec.md
 * § Partial updates).
 */
function changed(values: FormValues, saved: Saved): UpdateWeddingRequest {
  const guaranteed =
    values.guaranteedHeadcount === '' ? null : Number(values.guaranteedHeadcount)

  return {
    ...(values.weddingDate === saved.weddingDate
      ? {}
      : { weddingDate: values.weddingDate }),
    ...(guaranteed === saved.guaranteedHeadcount
      ? {}
      : { guaranteedHeadcount: guaranteed }),
  }
}

type FieldErrors = Partial<Record<keyof FormValues, string>>

/**
 * The two things the client can know are wrong without asking, both of which
 * the API would answer with a 400 that does not say which field it was (`#63`).
 *
 * THERE IS NO RULE ABOUT WHICH DATES ARE ALLOWED and no upper bound on
 * 보증인원 — inventing either here would be a product decision nobody has made,
 * and a past date is accepted on purpose: building the ledger after the fact is
 * a real case.
 */
function validate(values: FormValues): FieldErrors {
  const errors: FieldErrors = {}
  if (values.weddingDate === '') errors.weddingDate = '예식일을 입력해 주세요.'
  if (values.guaranteedHeadcount !== '') {
    if (!/^\d+$/.test(values.guaranteedHeadcount))
      errors.guaranteedHeadcount = '보증인원은 숫자로 입력해 주세요.'
    else if (Number(values.guaranteedHeadcount) < 1)
      errors.guaranteedHeadcount = '보증인원은 1명 이상으로 입력해 주세요.'
  }
  return errors
}

/**
 * What a failed save says.
 *
 * ONE MESSAGE FOR BOTH 400s. `VALIDATION_FAILED` and `MALFORMED_REQUEST_BODY`
 * differ only in whether the body had been read yet; to the couple they mean
 * the same thing, and the spec is explicit that no different UI is built for
 * them. A 404 is one wedding answer for four causes, none of which this form can
 * explain, so it falls to the generic message.
 *
 * A 401 PRODUCES NOTHING AT ALL: the session was already written to `null` and
 * the login screen is replacing this one, so "log in again" is not an error to
 * report here (lib/queryClient.ts).
 */
function failureMessage(error: unknown): string | null {
  if (error instanceof ApiError && error.status === 401) return null
  if (error instanceof ApiError && error.status === 400)
    return '저장하지 못했습니다. 입력한 내용을 확인해 주세요.'
  return '저장하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
