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
 * 웨딩 만들기 — 최초 1회. 날짜와 두 사람 이름, 그 이상은 묻지 않는다.
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
  const [values, setValues] = useState<CreateWeddingRequest>({
    weddingDate: '',
    groomName: '',
    brideName: '',
  })

  /*
   * Errors are derived, not stored: they appear on the first submit and then
   * disappear as each field is fixed, with no second copy of the form's state
   * to keep in step. Validating on every keystroke from the start would mean
   * telling someone their name is blank while they are still typing it.
   */
  const [submitted, setSubmitted] = useState(false)
  const request = trimmed(values)
  const errors = submitted ? validate(request) : {}

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    // A submit is a user action, so it is handled here — never in an Effect
    // watching a flag.
    event.preventDefault()
    setSubmitted(true)

    // Nothing on the server refuses a second wedding by the same person: one
    // person may belong to several, by design. So the only thing standing
    // between a double press and a duplicate ledger is this.
    if (create.isPending) return
    if (Object.keys(validate(request)).length > 0) return

    create.mutate(request, {
      onSuccess: () => navigate(ledgerPath, { replace: true }),
    })
  }

  const failure = create.isError ? failureMessage(create.error) : null

  return (
    <Screen>
      <div className="flex w-full flex-col gap-3">
        <h1 className="font-display text-title tracking-display">웨딩 만들기</h1>
        <p className="text-body leading-body text-ink-muted">
          예식일과 두 분의 이름만 있으면 시작할 수 있습니다.
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
        <Field
          error={errors.groomName}
          id="groom-name"
          label="신랑 이름"
          onChange={(event) => setValues({ ...values, groomName: event.target.value })}
          type="text"
          value={values.groomName}
        />
        <Field
          error={errors.brideName}
          id="bride-name"
          label="신부 이름"
          onChange={(event) => setValues({ ...values, brideName: event.target.value })}
          type="text"
          value={values.brideName}
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

/**
 * What is sent, which is not quite what was typed.
 *
 * THE NAMES ARE TRIMMED HERE BECAUSE THE SERVER MEASURES BEFORE ITS OWN TRIM:
 * 100 characters plus a trailing space is a 400 even though it would have been
 * stored as 100 (docs/api-spec.md § POST /weddings). Trimming in the client is
 * what makes the two agree — and it is a normalisation of what the person typed,
 * not a computation, which stays where it belongs.
 *
 * The date is passed through untouched. It is already `YYYY-MM-DD`.
 */
function trimmed(values: CreateWeddingRequest): CreateWeddingRequest {
  return {
    weddingDate: values.weddingDate,
    groomName: values.groomName.trim(),
    brideName: values.brideName.trim(),
  }
}

type FieldErrors = Partial<Record<keyof CreateWeddingRequest, string>>

/**
 * The three things the client can know are wrong without asking. Every one of
 * them would be a 400 from the server, so checking here spends no round trip
 * and says which field it was — which the 400 itself does not (`#63`).
 *
 * THERE IS NO RULE ABOUT WHICH DATES ARE ALLOWED, and adding one here would be
 * inventing a product decision nobody has made. A past date is accepted by the
 * API on purpose: building the ledger after the fact is a real case.
 */
function validate(request: CreateWeddingRequest): FieldErrors {
  const errors: FieldErrors = {}
  if (request.weddingDate === '') errors.weddingDate = '예식일을 입력해 주세요.'
  const names = [
    ['groomName', request.groomName, '신랑'],
    ['brideName', request.brideName, '신부'],
  ] as const
  for (const [key, name, who] of names) {
    // Measured on the trimmed value, in UTF-16 code units — the same unit and
    // the same value the server will count.
    if (name === '') errors[key] = `${who} 이름을 입력해 주세요.`
    else if (name.length > NAME_MAX)
      errors[key] = `이름은 ${NAME_MAX}자까지 쓸 수 있습니다.`
  }
  return errors
}

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
