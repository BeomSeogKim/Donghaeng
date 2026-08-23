import { Link } from 'react-router'
import { ledgerPath } from '../lib/routes'
import { buttonClassName } from './Button'
import { LogoutButton } from './LogoutButton'
import { Screen } from './Screen'

/**
 * 이미 다른 결혼식에 속해 있습니다 — the one screen both 웨딩 만들기 and 초대 수락
 * put up when the API answers 409 `ALREADY_IN_A_WEDDING`.
 *
 * IT IS ONE COMPONENT BECAUSE IT IS ONE ANSWER. The two screens are told the
 * same fact about the same account by the same check, and two spellings of it
 * is how the next reader learns whichever one they meet first
 * (`lib/alreadyInAWedding.ts`).
 *
 * IT IS NOT AN ERROR SCREEN. The ordinary way here is that the couple's other
 * tab already made the wedding, and then 내 하객 명부 열기 is not a consolation — it
 * is the recovery, and the list usually agrees the moment it is asked. The
 * screen exists for when it does not.
 *
 * 로그아웃 IS THE OTHER RECOVERY, NOT A LAST RESORT, which is why it is on this
 * screen rather than only under a form. The couple share phones, and being
 * signed in as the wrong Google account produces exactly this refusal — so
 * signing out and back in is the way through, and the verdict is dropped when
 * the session is.
 *
 * AND IT IS NAMED, because a reachable exit is not a guessable one. **Nothing
 * anywhere in this flow says who is signed in**: that is why the token is kept
 * alive through this 409 in the first place — the refusal is the only signal
 * the wrong account produces — and 마이페이지, which does name the account
 * (`#159`), sits behind 원장, which is precisely what this person cannot open.
 * Somebody reading 이미 다른 결혼식에 속해 있습니다 believes it, because it is true
 * of the account they are in; it is simply useless about the one they meant.
 *
 * THE SENTENCE IS CONDITIONAL AND SITS WITH 로그아웃, NOT IN THE BLOCK. The block
 * states the refusal, which is settled fact. This speaks only to the person for
 * whom 내 하객 명부 열기 did not open anything, and stays quiet for the ordinary
 * case — somebody who really does have their own ledger and opened a friend's
 * link, who is one tap from where they belong.
 *
 * The EmptyState part: a bordered block on surface, never a floating card, and
 * never an illustration. The exit sits outside the block, the way 웨딩 만들기 and
 * 초대 수락 both hold it — a way off the screen rather than one of its answers.
 */
export function AlreadyInAWedding() {
  return (
    <Screen>
      <div className="flex w-full flex-col items-start gap-3 border border-line bg-surface px-6 py-8">
        <h1 className="text-lead font-semibold leading-snug">
          이미 다른 결혼식에 속해 있습니다
        </h1>
        <p className="text-body leading-body text-ink-muted">
          한 사람은 하나의 결혼식에만 속할 수 있습니다.
        </p>
        <Link className={buttonClassName('secondary')} to={ledgerPath}>
          내 하객 명부 열기
        </Link>
      </div>
      <div className="flex w-full flex-col items-center gap-3 text-center">
        <p className="text-body leading-body text-ink-muted">
          명부가 열리지 않으면 다른 계정으로 로그인한 것일 수 있습니다.
        </p>
        <LogoutButton className="flex flex-col items-center gap-2 text-center" />
      </div>
    </Screen>
  )
}
