import { buttonClassName } from '../components/Button'
import { useLogout } from '../hooks/useLogout'
import type { Session } from '../lib/api-shapes'

/**
 * What a signed-in person sees. The ledger takes this place when it exists
 * (`#5` and after); until then this screen's whole job is to prove the session
 * is real and to give it back.
 */
export function HomePage({ session }: { session: Session }) {
  const logout = useLogout()

  return (
    <main className="min-h-[100dvh] bg-ground text-ink">
      <header className="flex items-center justify-between gap-4 border-b border-line px-4 py-3">
        <span className="font-display text-title tracking-display">동행</span>
        <button
          className={buttonClassName('secondary')}
          disabled={logout.isPending}
          onClick={() => logout.mutate()}
          type="button"
        >
          로그아웃
        </button>
      </header>

      <section className="flex flex-col gap-2 px-4 py-6">
        {/* The provider may return no name, so this is never assumed to be a
            string (docs/api-spec.md § GET /auth/me). */}
        <p className="text-lead">{`${session.name ?? '이름 없음'} 님`}</p>
        <p className="text-body leading-body text-ink-muted">
          원장 화면은 아직 만들어지지 않았습니다.
        </p>
        {logout.isError && (
          <p className="text-body leading-body text-danger">
            로그아웃하지 못했습니다. 연결을 확인하고 다시 눌러 주세요.
          </p>
        )}
      </section>
    </main>
  )
}
