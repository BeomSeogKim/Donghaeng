import { type FormEvent, useState } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { BrandMark } from '../components/BrandMark'
import { buttonClassName } from '../components/Button'
import { Field } from '../components/Field'
import { LogoutButton } from '../components/LogoutButton'
import { Screen } from '../components/Screen'
import { type CreateWeddingRequest, useCreateWedding } from '../hooks/useCreateWedding'
import { useWeddings } from '../hooks/useWeddings'
import { ApiError } from '../lib/api'
import { ledgerPath } from '../lib/routes'

/*
 * 웨딩 만들기 — 최초 1회. 예식일과 내가 누구인지, 그 이상은 묻지 않는다.
 *
 * NOBODY TYPES THEIR PARTNER'S NAME HERE (changed 2026-08-22,
 * notes/2026-08-22-decision-the-couples-two-seats.md). 신랑 이름과 신부 이름은
 * 사람의 속성이지 웨딩의 속성이 아니다 — and a required field had to be filled
 * by whoever arrived first, which meant asking one partner to guess, abbreviate
 * or invent the other's name on a screen they see once. The wedding is created
 * with both of its seats; the caller fills theirs and the other waits.
 *
 * 보증인원 IS NOT ASKED HERE, AND THAT IS THE SCREEN'S MAIN DECISION
 * (notes/2026-08-07-design-screens-and-flow.md). Couples sign up before booking
 * a venue, so at this moment the venue's number does not exist, and asking for
 * an unknown on the first screen is where people quit. The ledger works
 * completely without it; it arrives later in 설정 (`#8`). Meal types are not
 * asked for the same reason — the default is one type, and the moment a couple
 * first meets meal types is when a guest needs 유아식.
 *
 * IT IS PASSED ONCE AND NEVER RETURNED TO, which is why the redirect below is a
 * `replace`: Back from the ledger must not land on a form for a wedding that
 * already exists. Editing wedding information is 설정, not this screen.
 *
 * THE DATE IS A NATIVE `type="date"`. Mobile is the primary device, so the
 * control worth having is the one the phone already has — and its value is
 * `YYYY-MM-DD`, which is exactly what the API wants, so nothing formats or
 * parses a date anywhere in this file.
 */
export function CreateWeddingPage() {
  const weddings = useWeddings()

  /*
   * THE FORM IS REACHABLE ONLY WHILE THE LIST IS EMPTY — the mirror of 원장's
   * redirect, and the guard the record already claimed was here. v1 has no
   * wedding switcher and no delete, so a second wedding would take `[0]` and
   * leave the first one with no route, no link and no way back: a 400-row
   * ledger, unreachable. `create.isPending` cannot stand in for this, because
   * it is one component's state and the way here is a bookmark, a typed URL, or
   * a second tab still parked on the form after the first one submitted.
   *
   * A FAILED READ SHOWS NEITHER. Not knowing whether they already have a
   * wedding is exactly the case where offering the form is expensive, and the
   * cost is asymmetric: a retry costs a tap, a wrongly-offered form costs the
   * ledger.
   */
  if (weddings.isPending) {
    return (
      <Screen>
        <BrandMark />
      </Screen>
    )
  }

  if (weddings.isError) {
    return (
      <Screen>
        <BrandMark />
        <div className="flex w-full flex-col items-center gap-4">
          <p className="text-body leading-body text-ink-muted">
            웨딩 정보를 불러오지 못했습니다
          </p>
          <button
            className={buttonClassName('secondary')}
            onClick={() => void weddings.refetch()}
            type="button"
          >
            다시 시도
          </button>
        </div>
      </Screen>
    )
  }

  if (weddings.data.length > 0) return <Navigate replace to={ledgerPath} />

  return <CreateWeddingForm />
}

function CreateWeddingForm() {
  const navigate = useNavigate()
  const create = useCreateWedding()
  const [values, setValues] = useState<FormValues>({
    weddingDate: '',
    side: null,
    name: '',
  })

  /*
   * Errors are derived, not stored: they appear on the first submit and then
   * disappear as each field is fixed, with no second copy of the form's state
   * to keep in step. Validating on every keystroke from the start would mean
   * telling someone their name is blank while they are still typing it.
   */
  const [submitted, setSubmitted] = useState(false)
  const sending = trimmed(values)
  const errors = submitted ? validate(sending) : {}

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    // A submit is a user action, so it is handled here — never in an Effect
    // watching a flag.
    event.preventDefault()
    setSubmitted(true)

    // The server does refuse a second wedding by the same person — 409
    // ALREADY_IN_A_WEDDING, since `#158` — but the client has no path for that
    // answer yet (`#164`), so this is what keeps a double press from becoming an
    // error the couple has to read.
    if (create.isPending) return

    // `side === null` is one of the errors below; naming it again is what
    // narrows the type, because a compiler cannot read a message off a map.
    if (Object.keys(validate(sending)).length > 0 || sending.side === null) return

    create.mutate(
      { weddingDate: sending.weddingDate, side: sending.side, name: sending.name },
      { onSuccess: () => navigate(ledgerPath, { replace: true }) },
    )
  }

  const failure = create.isError ? failureMessage(create.error) : null

  return (
    <Screen>
      <div className="flex w-full flex-col gap-3">
        <h1 className="font-display text-title tracking-display">웨딩 만들기</h1>
        <p className="text-body leading-body text-ink-muted">
          예식일과 본인 이름만 있으면 시작할 수 있습니다. 상대방 이름은 적지 않습니다.
        </p>
      </div>

      <form className="flex w-full flex-col gap-5" onSubmit={handleSubmit}>
        <Field
          error={errors.weddingDate}
          id="wedding-date"
          label="예식일"
          onChange={(event) => setValues({ ...values, weddingDate: event.target.value })}
          type="date"
          value={values.weddingDate}
        />
        <SideChoice
          error={errors.side}
          onChange={(side) => setValues({ ...values, side })}
          value={values.side}
        />
        <Field
          error={errors.name}
          id="my-name"
          label="내 이름"
          onChange={(event) => setValues({ ...values, name: event.target.value })}
          type="text"
          value={values.name}
        />

        {failure !== null && (
          // Announced, because it appears away from the button that was just
          // pressed and nothing else on the screen changes to say so.
          <p className="text-body leading-body text-danger" role="alert">
            {failure}
          </p>
        )}

        <button
          className={`${buttonClassName('primary')} w-full`}
          disabled={create.isPending}
          type="submit"
        >
          만들기
        </button>
      </form>

      {/* THE ONLY EXIT THIS SCREEN HAS, and the reason it is here rather than
          on 원장 alone: a person whose `GET /weddings` answers `[]` is sent
          here and cannot leave. That is not only 최초 1회 — an empty list is
          also what being removed from a partner's wedding looks like
          (docs/api-spec.md § GET /weddings), and that person would otherwise
          be parked on a form they have no reason to fill in, signed in, with
          nothing to press. */}
      <LogoutButton className="flex flex-col items-center gap-2 text-center" />
    </Screen>
  )
}

type Side = CreateWeddingRequest['side']

/**
 * The form, which is not the request: `side` starts as "not answered yet", and
 * `CreateWeddingRequest` has no way to say that. Keeping the two types apart is
 * what keeps "neither side chosen" a state the screen can hold rather than a
 * default it has to invent.
 */
type FormValues = {
  weddingDate: string
  side: Side | null
  name: string
}

/**
 * What is sent, which is not quite what was typed.
 *
 * THE NAME IS TRIMMED HERE BECAUSE THE SERVER MEASURES BEFORE ITS OWN TRIM:
 * 100 characters plus a trailing space is a 400 even though it would have been
 * stored as 100 (docs/api-spec.md § POST /weddings). Trimming in the client is
 * what makes the two agree — and it is a normalisation of what the person typed,
 * not a computation, which stays where it belongs.
 *
 * The date is passed through untouched. It is already `YYYY-MM-DD`.
 */
function trimmed(values: FormValues): FormValues {
  return { ...values, name: values.name.trim() }
}

type FieldErrors = Partial<Record<keyof FormValues, string>>

/**
 * The three things the client can know are wrong without asking. Every one of
 * them would be a 400 from the server, so checking here spends no round trip
 * and says which field it was — which the 400 itself does not (`#63`).
 *
 * THERE IS NO RULE ABOUT WHICH DATES ARE ALLOWED, and adding one here would be
 * inventing a product decision nobody has made. A past date is accepted by the
 * API on purpose: building the ledger after the fact is a real case.
 */
function validate(values: FormValues): FieldErrors {
  const errors: FieldErrors = {}
  if (values.weddingDate === '') errors.weddingDate = '예식일을 입력해 주세요.'
  if (values.side === null) errors.side = '신랑인지 신부인지 골라 주세요.'
  // Measured on the trimmed value, in UTF-16 code units — the same unit and the
  // same value the server will count.
  if (values.name === '') errors.name = '이름을 입력해 주세요.'
  else if (values.name.length > NAME_MAX)
    errors.name = `이름은 ${NAME_MAX}자까지 쓸 수 있습니다.`
  return errors
}

/** 신랑 먼저, the order every `seats` array uses. */
const SIDES: readonly (readonly [Side, string])[] = [
  ['GROOM', '신랑입니다'],
  ['BRIDE', '신부입니다'],
]

/**
 * 나는 신랑입니다 / 신부입니다.
 *
 * NEITHER IS PRESELECTED, AND THAT IS THE DECISION IN THIS FILE. This is the
 * first thing a person tells us about themselves and there is no side that is
 * safe to assume — a default would be wrong for half of everyone, and wrong here
 * writes their name onto their partner's seat of a ledger they will keep for
 * months. An unchosen radio group is not an unfinished form; it is the honest
 * state of a question nobody has answered.
 *
 * NATIVE RADIOS PAINTED AS A SEGMENTED CONTROL, the shape the design system
 * already has (design/components/parts/13-attendance-control.html). Two
 * half-width cells at the 44px tap floor is what makes it hard to get wrong with
 * a thumb, which is this screen's primary device — and the platform control is
 * what supplies arrow-key movement, the group's label and "which one is chosen"
 * to a screen reader, none of which a pair of styled buttons would have.
 */
function SideChoice({
  error,
  onChange,
  value,
}: {
  error?: string
  onChange: (side: Side) => void
  value: Side | null
}) {
  const invalid = error !== undefined

  return (
    /* A div with `role="radiogroup"` rather than a fieldset: the group is what
       carries `aria-invalid` and points at the error, and `aria-invalid` is a
       supported state of `radiogroup` while `group` — a fieldset's implicit
       role — does not list it. The label is joined by id for the same reason. */
    <div
      aria-describedby={invalid ? SIDE_ERROR_ID : undefined}
      aria-invalid={invalid ? true : undefined}
      aria-labelledby={SIDE_LABEL_ID}
      className="flex flex-col gap-2"
      role="radiogroup"
    >
      <span className="text-meta font-semibold text-ink" id={SIDE_LABEL_ID}>
        나는
      </span>
      <div
        className={`grid grid-cols-2 overflow-hidden rounded-control border ${
          invalid ? 'border-danger' : 'border-line-strong'
        }`}
      >
        {SIDES.map(([side, label], index) => (
          <label
            className={`${SIDE_CELL} ${index > 0 ? 'border-l border-line' : ''}`}
            key={side}
          >
            <input
              checked={value === side}
              className="sr-only"
              name="side"
              onChange={() => onChange(side)}
              type="radio"
              value={side}
            />
            {label}
          </label>
        ))}
      </div>
      {invalid && (
        <p className="text-meta leading-snug text-danger" id={SIDE_ERROR_ID}>
          {error}
        </p>
      )}
    </div>
  )
}

const SIDE_LABEL_ID = 'side-label'
const SIDE_ERROR_ID = 'side-error'

/*
 * The chosen side is filled with 자적 — the same fill the pressed filter chip
 * uses, and one of the few places this product fills colour at all. It has to be
 * readable at a glance mid-form, because it is the one answer here that has no
 * text to fall back on.
 *
 * The focus ring is offset INWARD: the cells sit inside a clipped rounded
 * border, so an outline drawn outside the cell is an outline nobody sees.
 */
const SIDE_CELL =
  'flex min-h-[var(--dh-tap-min)] cursor-pointer items-center justify-center ' +
  'bg-surface px-3 text-body font-semibold text-ink-muted transition-colors ' +
  'duration-(--dh-dur-fast) ease-standard ' +
  'has-[:checked]:bg-primary has-[:checked]:text-ink-on-accent ' +
  'has-[:focus-visible]:outline-2 has-[:focus-visible]:-outline-offset-2 ' +
  'has-[:focus-visible]:outline-focus'

/** The column's limit, and the API's (docs/api-spec.md § POST /weddings). */
const NAME_MAX = 100

/**
 * What a failed create says.
 *
 * ONE MESSAGE FOR BOTH 400s. `VALIDATION_FAILED` and `MALFORMED_REQUEST_BODY`
 * differ only in whether the body had been read yet; to the couple they mean
 * the same thing, and the spec is explicit that no different UI is built for
 * them. Any other status — including a 415 the request wrapper should have made
 * impossible — is the generic one, because an unrecognised failure is handled
 * as a generic failure of its status.
 *
 * A 401 produces nothing at all: the session was already written to null, the
 * guard is putting the login screen up, and "log in again" is not an error to
 * report. A 5xx says nothing about what went wrong by design, so there is
 * nothing to explain and only something to try again.
 */
function failureMessage(error: unknown): string | null {
  if (error instanceof ApiError && error.status === 401) return null
  if (error instanceof ApiError && error.status === 400)
    return '예식일과 이름을 다시 확인해 주세요.'
  return '웨딩을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
