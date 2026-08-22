import { LogoutButton } from '../components/LogoutButton'
import { Section, SubScreen } from '../components/SubScreen'
import type { Session } from '../hooks/useSession'
import { settingsPath } from '../lib/routes'

/*
 * 마이페이지 — 지금 누구로 로그인해 있는지, and the way out.
 *
 * IT EXISTS BECAUSE TWO ACCOUNTS EDIT ONE 원장. The couple each sign in as
 * themselves and share phones, and until this screen the app said nowhere whose
 * session was open — so a person could edit the ledger signed in as their
 * partner without ever being told (`#159`).
 *
 * IT IS THE ACCOUNT, NOT THE WEDDING. 설정 holds what the wedding has; this
 * holds what the person is. They are two subjects and only the trip between
 * them is shared.
 *
 * NO EDIT, AND THAT IS THE SPEC RATHER THAN A CUT. `name` is the provider's
 * display name, re-read at every login: a field here would accept a name and
 * lose it at the next sign-in (docs/api-spec.md § GET /auth/me). There is
 * deliberately no `email` either, and this screen does not go looking for one.
 *
 * THE PERSON COMES FROM THE ROUTE TABLE, not from a second read. `GET /auth/me`
 * is already resolved above the table — nothing renders before "am I logged
 * in?" is answered — so the answer is handed down rather than asked for again,
 * and this screen has no "signed out" branch to write for a state the guard
 * makes unreachable.
 */
export function MyPage({ person }: { person: Session }) {
  const name = person.name ?? null

  return (
    <SubScreen back={{ label: '설정', to: settingsPath }} title="마이페이지">
      <Section title="로그인한 계정">
        {/* SHOW WHAT YOU HAVE, and say so when there is nothing. A provider may
            return no name at all, which is an ordinary answer and not an error
            — so it is stated as the fact it is, muted, rather than left as a
            blank where the answer to "who am I" belongs. 원장 names an empty
            seat the same way. */}
        <p
          className={`text-lead leading-snug ${name === null ? 'text-ink-muted' : 'font-semibold'}`}
        >
          {name ?? '이름 없음'}
        </p>
        {/* NAMED, NOT "계정" — the heading above already spends that word on
            ours. v1 signs in with Google alone (`#89` is post-v1), so this line
            moves when a second provider does. */}
        <p className="mt-2 text-body leading-body text-ink-muted">
          이름은 구글 계정에서 가져옵니다. 다음 로그인에 다시 채워지므로 여기서는 바꿀 수
          없습니다.
        </p>

        {/* 로그아웃'S HOME. It left 원장's pinned header with `#195` — a second
            sticky row spent vertical space on every scroll of the ledger for an
            action taken about once a session — and it did not move to 설정,
            which is passed through rather than inhabited
            (notes/2026-08-22-decision-logout-leaves-the-ledger.md). */}
        <LogoutButton className="mt-6 flex flex-col items-start gap-2" />
      </Section>
    </SubScreen>
  )
}
