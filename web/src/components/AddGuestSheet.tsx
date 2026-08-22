import { type FormEvent, useRef, useState } from 'react'
import { type AddGuestRequest, useAddGuest } from '../hooks/useAddGuest'
import { GROUP_LABELS } from '../hooks/useGuests'
import { ApiError } from '../lib/api'
import { buttonClassName } from './Button'
import { Choice, type ChoiceOption } from './Choice'
import { Field, SelectField } from './Field'

/*
 * 하객 추가 — the sheet that opens over 원장
 * (notes/2026-08-07-design-screens-and-flow.md).
 *
 * IT IS THE ONLY WAY A ROW ENTERS A LEDGER IN v1. The vendor-email parser and
 * the CSV import went to post-v1 on 2026-08-21, so every guest of every couple
 * is typed here — which is why this is treated as a screen with a phone as its
 * primary device rather than as a dialog bolted onto the list. Attendance
 * normally reaches a couple through their parents and KakaoTalk, so direct
 * entry is the main road and not a fallback.
 *
 * A SHEET, NOT A ROUTE. There is essentially one screen in this product and
 * everything else opens over it; a route would put a navigation between the
 * couple and the list they are filling, and Back would then mean two different
 * things on the same screen.
 *
 * IT STAYS OPEN AFTER A GUEST IS ADDED — the decision in this file
 * (notes/2026-08-22-decision-guest-add-sheet.md). A couple works down a list
 * their parents sent them, so closing per guest charges a re-open tap for every
 * row of a 200-row ledger, on the product whose whole claim is that it is less
 * work than the spreadsheet.
 *
 * WHAT IS KEPT BETWEEN GUESTS IS THE 측 AND THE 그룹, BOTH
 * (notes/2026-08-22-decision-the-sheet-carries-both.md). Those lists arrive in
 * BLOCKS — "신랑 친구 20명", then "직장 15명" — and inside a block neither one
 * changes; direct entry is the only intake path in v1, so that is every row of
 * every ledger rather than a corner case. Both are filled, visible controls, so
 * what survives is the couple's own previous answer still on screen and not a
 * default we invented: A SHEET OPENED FRESH STILL PRE-ANSWERS NOTHING.
 *
 * IT STANDS ON `#12` (하객 상세 — 수정·삭제) SHIPPING IN v1, which is what makes
 * a carried-over mistake fixable from the row. If `#12` leaves v1 the answer
 * inverts to resetting both — a mistake nobody can correct is worth more than
 * the taps — and this is the code that has to move with it.
 *
 * EVERYTHING IS ON ONE SCROLL, with no "더 보기". 하객 수정 (`#12`) does not
 * exist, so a member that is not on this sheet cannot be set at all in v1.
 */
export function AddGuestSheet({
  onClose,
  weddingId,
}: {
  onClose: () => void
  weddingId: number
}) {
  const add = useAddGuest(weddingId)
  const nameField = useRef<HTMLInputElement>(null)
  const [values, setValues] = useState<FormValues>(EMPTY)

  /*
   * Errors are derived, not stored: they appear on the first submit and then
   * disappear as each field is fixed, with no second copy of the form's state
   * to keep in step. Validating from the first keystroke would mean telling
   * someone their guest has no name while they are still typing it.
   */
  const [submitted, setSubmitted] = useState(false)
  const sending = trimmed(values)
  const errors = submitted ? validate(sending) : {}

  function change(patch: Partial<FormValues>) {
    setValues((current) => ({ ...current, ...patch }))
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    // A submit is a user action, so it is handled here — never in an Effect
    // watching a flag.
    event.preventDefault()
    setSubmitted(true)

    // A create is not idempotent: a second guest with the same name succeeds
    // and is a second row (docs/api-spec.md). A double press must not be two
    // people in the ledger.
    if (add.isPending) return

    // `side === null` is one of the errors below; naming it again is what
    // narrows the type, because a compiler cannot read a message off a map.
    if (Object.keys(validate(sending)).length > 0 || sending.side === null) return

    add.mutate(request(sending, sending.side), {
      onSuccess: () => {
        // 측 and 그룹 both survive — see the note at the top of this file.
        setValues({
          ...EMPTY,
          side: sending.side,
          groupCategory: sending.groupCategory,
        })
        setSubmitted(false)
        nameField.current?.focus()
      },
    })
  }

  const failure = add.isError ? failureMessage(add.error) : null

  return (
    // The one thing in this product that floats, so the one place the overlay
    // shadow and the sheet radius are used. It rises from the bottom edge on a
    // phone and is centred on a laptop.
    <div className="fixed inset-0 z-20 flex items-end justify-center bg-scrim md:items-center">
      <div
        aria-labelledby={TITLE_ID}
        aria-modal="true"
        className="flex max-h-[92dvh] w-full max-w-96 flex-col rounded-t-sheet border border-line bg-surface shadow-overlay md:max-h-[88dvh] md:rounded-sheet"
        onKeyDown={(event) => {
          // Escape closes it. The event reaches here from whatever inside the
          // sheet has focus, and something always does — the name field takes
          // it when the sheet opens.
          if (event.key === 'Escape') onClose()
        }}
        role="dialog"
      >
        <div
          aria-hidden="true"
          className="mx-auto mt-2 h-1 w-9 rounded-chip bg-line-strong"
        />
        <div className="border-b border-line px-4 py-3">
          <h2
            className="font-display text-title leading-tight tracking-display"
            id={TITLE_ID}
          >
            하객 추가
          </h2>
        </div>

        <form className="contents" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-4 overflow-y-auto px-4 py-4">
            {/*
             * WHAT THE WRITE DID, said where the couple is looking. The sheet
             * covers the pinned 인원수 on a phone, so without this the one
             * thing the product exists to show — the number moving — happens
             * behind the couple's own hands. It is the number this create's own
             * response carried, not a second read of it (docs/api-spec.md).
             */}
            {add.isSuccess && (
              <p
                className="text-body leading-body tabular-nums text-att-yes-fg"
                role="status"
              >
                {`${add.data.guest.name}님을 추가했습니다 · 식대 인원 ${add.data.headcount.mealHeadcount}명`}
              </p>
            )}

            <Field
              autoComplete="off"
              // Focused on open — the sheet is a dialog the couple pressed a
              // button to get, and this is the field they pressed it for.
              autoFocus
              error={errors.name}
              id="guest-name"
              label="이름"
              onChange={(event) => change({ name: event.target.value })}
              ref={nameField}
              type="text"
              value={values.name}
            />

            {/*
             * 측 HAS NO DEFAULT AND NEVER GETS ONE. `wedding_side` holds 신랑측
             * and 신부측 and nothing else — there is no residual value the way
             * `기타` is one for the group — so a default would be a claim the
             * couple never made, on one of the ledger's two filters and one of
             * its aggregation axes
             * (notes/2026-08-20-decision-guest-entry-side-and-companions.md).
             */}
            <Choice
              error={errors.side}
              id="guest-side"
              label="측"
              name="guest-side"
              onChange={(side) => change({ side })}
              options={SIDES}
              value={values.side}
            />

            {/* 기본값은 참석이다 — 부부는 다르다는 얘기를 들었을 때 고친다. The
                control is never presented as unset, and 불참 is neutral: a guest
                who cannot come is a fact, not an error. */}
            <Choice
              id="guest-attending"
              label="예상 참석 여부"
              name="guest-attending"
              onChange={(attending) => change({ attending })}
              options={ATTENDING}
              value={values.attending}
            />

            <PartySize
              onChange={(expectedPartySize) => change({ expectedPartySize })}
              value={values.expectedPartySize}
            />

            {/* The axis the ledger aggregates on. 기타 is the default because it
                honestly means "not stated yet". */}
            <SelectField
              id="guest-group"
              label="그룹"
              onChange={(event) =>
                change({ groupCategory: event.target.value as GroupCategory })
              }
              value={values.groupCategory}
            >
              {GROUPS.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </SelectField>

            {/* THE COUPLE'S OWN LABEL, AND NEVER AN AGGREGATION AXIS. It is
                collected and shown on the row, and it is deliberately not
                offered as a filter or a grouping: free labels fracture on typing
                variants, and a fractured group is a wrong number. */}
            <Field
              error={errors.groupLabel}
              id="guest-group-label"
              label="그룹 라벨"
              onChange={(event) => change({ groupLabel: event.target.value })}
              placeholder="예: 대학교 동아리"
              type="text"
              value={values.groupLabel}
            />

            <Field
              autoComplete="off"
              error={errors.contact}
              id="guest-contact"
              inputMode="tel"
              label="연락처"
              onChange={(event) => change({ contact: event.target.value })}
              type="tel"
              value={values.contact}
            />

            {/* 배려사항 belongs to the person and carries forward to seating. */}
            <Field
              error={errors.accessibilityNote}
              id="guest-accessibility-note"
              label="배려사항"
              onChange={(event) => change({ accessibilityNote: event.target.value })}
              placeholder="예: 휠체어 좌석"
              type="text"
              value={values.accessibilityNote}
            />

            {failure !== null && (
              // Announced, because it appears away from the button that was
              // just pressed and nothing else on the sheet changes to say so.
              <p className="text-body leading-body text-danger" role="alert">
                {failure}
              </p>
            )}
          </div>

          <div className="flex gap-2 border-t border-line px-4 pt-3 pb-5">
            <button
              className={buttonClassName('secondary')}
              onClick={onClose}
              type="button"
            >
              닫기
            </button>
            <button
              className={`${buttonClassName('primary')} flex-1`}
              disabled={add.isPending}
              type="submit"
            >
              추가
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

const TITLE_ID = 'add-guest-title'

type Side = AddGuestRequest['side']
type GroupCategory = NonNullable<AddGuestRequest['groupCategory']>
type Attending = 'ATTENDING' | 'NOT_ATTENDING'

/**
 * The form, which is not the request.
 *
 * `side` starts as "not answered yet" and `AddGuestRequest` has no way to say
 * that; the optional strings are held as `''` because that is what an untouched
 * input holds, and what is sent for one is `null`. Keeping the two types apart
 * is what keeps an unanswered question a state the sheet can be in rather than
 * a default it has to invent.
 */
type FormValues = {
  name: string
  side: Side | null
  attending: Attending
  expectedPartySize: number
  groupCategory: GroupCategory
  groupLabel: string
  contact: string
  accessibilityNote: string
}

/** The API's own defaults, on the screen rather than left to be inferred. */
const EMPTY: FormValues = {
  name: '',
  side: null,
  attending: 'ATTENDING',
  expectedPartySize: 1,
  groupCategory: 'OTHER',
  groupLabel: '',
  contact: '',
  accessibilityNote: '',
}

/**
 * What is sent, which is not quite what was typed.
 *
 * EVERY LENGTH BOUND IS MEASURED ON WHAT YOU SEND, BEFORE THE SERVER'S OWN
 * TRIM: 100 characters plus a trailing space is a 400 even though it would have
 * been stored as 100. Trimming here is what makes the two agree, and it is a
 * normalisation of what the couple typed rather than a computation
 * (docs/api-spec.md § POST /weddings/{weddingId}/guests).
 */
function trimmed(values: FormValues): FormValues {
  return {
    ...values,
    name: values.name.trim(),
    groupLabel: values.groupLabel.trim(),
    contact: values.contact.trim(),
    accessibilityNote: values.accessibilityNote.trim(),
  }
}

/**
 * The body, built from a form that has been answered.
 *
 * EVERY MEMBER IS SENT, INCLUDING THE ONES AT THEIR DEFAULT. An omitted member
 * and an explicit `null` mean the same thing to the server, so nothing here has
 * to be assembled conditionally — and sending what the sheet shows keeps the
 * screen and the stored row the same statement rather than two that agree by
 * coincidence. `groupCategory` is the one member that may not be `null`: the
 * generated type gives an enum no null branch (docs/api-spec.md).
 */
function request(values: FormValues, side: Side): AddGuestRequest {
  return {
    name: values.name,
    side,
    groupCategory: values.groupCategory,
    groupLabel: orNull(values.groupLabel),
    contact: orNull(values.contact),
    accessibilityNote: orNull(values.accessibilityNote),
    expectedAttending: values.attending === 'ATTENDING',
    expectedPartySize: values.expectedPartySize,
  }
}

/** A field the couple left blank is nothing, never an empty string. */
function orNull(value: string): string | null {
  return value === '' ? null : value
}

type FieldErrors = Partial<Record<keyof FormValues, string>>

/**
 * What the client can know is wrong without asking. Every one of these would be
 * a 400 from the server, so checking here spends no round trip and says which
 * field it was — which the 400 itself does not (`#63`).
 *
 * The party size is not checked: the stepper cannot go below 1, so a party of
 * zero is unreachable rather than merely refused.
 */
function validate(values: FormValues): FieldErrors {
  const errors: FieldErrors = {}
  // Measured in UTF-16 code units on the trimmed value — the same unit and the
  // same value the server will count.
  if (values.name === '') errors.name = '이름을 입력해 주세요.'
  else if (values.name.length > NAME_MAX)
    errors.name = `이름은 ${NAME_MAX}자까지 쓸 수 있습니다.`
  if (values.side === null) errors.side = '신랑측인지 신부측인지 골라 주세요.'
  if (values.groupLabel.length > GROUP_LABEL_MAX)
    errors.groupLabel = `그룹 라벨은 ${GROUP_LABEL_MAX}자까지 쓸 수 있습니다.`
  if (values.contact.length > CONTACT_MAX)
    errors.contact = `연락처는 ${CONTACT_MAX}자까지 쓸 수 있습니다.`
  if (values.accessibilityNote.length > NOTE_MAX)
    errors.accessibilityNote = `배려사항은 ${NOTE_MAX}자까지 쓸 수 있습니다.`
  return errors
}

/**
 * The API's bounds (docs/api-spec.md § POST /weddings/{weddingId}/guests).
 *
 * THEY ARE CHECKED, NOT ENFORCED BY `maxLength`. A `maxLength` truncates a
 * paste without saying so, and a 배려사항 silently cut at 500 characters is the
 * couple's own words thrown away by a control that then reports success. Being
 * told which field is too long is the honest answer, and it is the one 웨딩
 * 만들기 already gives.
 */
const NAME_MAX = 100
const GROUP_LABEL_MAX = 100
const CONTACT_MAX = 30
const NOTE_MAX = 500

/** 신랑 먼저, the order every `seats` array uses. */
const SIDES: readonly ChoiceOption<Side>[] = [
  { value: 'GROOM', label: '신랑측', tone: 'primary' },
  { value: 'BRIDE', label: '신부측', tone: 'primary' },
]

const ATTENDING: readonly ChoiceOption<Attending>[] = [
  { value: 'ATTENDING', label: '참석', tone: 'yes' },
  { value: 'NOT_ATTENDING', label: '불참', tone: 'no' },
]

/** The seven, in the API's order — and there is no eighth. */
const GROUPS = Object.entries(GROUP_LABELS) as readonly (readonly [
  GroupCategory,
  string,
])[]

/** 참석 인원이 0인 파티는 파티가 아니다 — 불참이 그 말을 하는 자리다. */
const PARTY_MIN = 1

/**
 * 예상 인원 — the attending headcount INCLUDING the guest, not a companion
 * count. A couple bringing one guest is 2.
 *
 * A STEPPER RATHER THAN A NUMBER FIELD, from the sheet part: this is answered
 * with a thumb, and the answer is almost always within a tap or two of 1. The
 * floor is 1 and the button below it is disabled rather than refused — a size
 * of 0 is a 400, and 불참 is how the couple says nobody is coming.
 *
 * The party is a count with no 측 and no attendance of its own: a companion
 * follows the head guest, and a guest marked 불참 contributes zero to the meal
 * headcount whatever the size says. A party that splits is not one row — that
 * person is registered as their own guest
 * (notes/2026-08-20-decision-guest-entry-side-and-companions.md).
 */
function PartySize({
  onChange,
  value,
}: {
  onChange: (value: number) => void
  value: number
}) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-meta font-semibold text-ink">예상 인원</legend>
      <div className="flex items-center gap-2">
        <button
          aria-label="예상 인원 줄이기"
          className={STEP}
          disabled={value <= PARTY_MIN}
          onClick={() => onChange(value - 1)}
          type="button"
        >
          −
        </button>
        {/* `output` announces its own change, so the count is spoken without a
            live region of our own. Tabular, like every digit that moves. */}
        <output className="min-w-8 text-center text-lead font-semibold tabular-nums">
          {value}
        </output>
        <button
          aria-label="예상 인원 늘리기"
          className={STEP}
          onClick={() => onChange(value + 1)}
          type="button"
        >
          +
        </button>
        <span className="ml-auto text-meta leading-snug text-ink-muted">
          본인을 포함한 인원
        </span>
      </div>
    </fieldset>
  )
}

const STEP =
  'flex min-h-[var(--dh-tap-min)] min-w-[var(--dh-tap-min)] items-center justify-center ' +
  'rounded-control border border-line-strong bg-surface text-lead text-ink ' +
  'transition-colors duration-(--dh-dur-fast) ease-standard ' +
  'hover:border-ink-faint disabled:cursor-not-allowed disabled:opacity-45'

/**
 * What a failed add says.
 *
 * ONE MESSAGE FOR BOTH 400s. `VALIDATION_FAILED` and `MALFORMED_REQUEST_BODY`
 * differ only in whether the body had been read yet; to the couple they mean
 * the same thing, and the spec is explicit that no different UI is built for
 * them. A 404 is the same one wedding answer for four causes and none of them
 * is anything this sheet can explain, so it falls to the generic message.
 *
 * A 401 PRODUCES NOTHING AT ALL: the session was already written to `null`, the
 * login screen is replacing the ledger underneath, and "log in again" is not an
 * error to report on a sheet that is about to be unmounted (lib/queryClient.ts).
 */
function failureMessage(error: unknown): string | null {
  if (error instanceof ApiError && error.status === 401) return null
  if (error instanceof ApiError && error.status === 400)
    return '하객을 추가하지 못했습니다. 입력한 내용을 확인해 주세요.'
  return '하객을 추가하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
