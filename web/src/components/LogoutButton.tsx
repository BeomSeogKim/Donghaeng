import { useLogout } from '../hooks/useLogout'
import { buttonClassName } from './Button'

/**
 * 로그아웃, and what to say when it does not happen.
 *
 * THE FAILURE MESSAGE IS THE REASON THIS IS A COMPONENT. `POST /auth/logout`
 * always answers 204 — no cookie, an expired session, one already revoked, all
 * of them mean "you are not logged in on this device" — so a non-204 means the
 * request never reached the API at all and the session is still live
 * (docs/api-spec.md § POST /auth/logout). A button that un-disables itself and
 * says nothing tells a couple sharing a phone that they signed out when they
 * did not, which is the one thing this screen may not do.
 *
 * IT IS ON THE SCREENS A SIGNED-IN PERSON CAN BE PARKED ON, and 원장 is not one
 * of them any more (`#195`). Three of them are: 마이페이지, which is the account
 * and where a person goes *to* sign out; 웨딩 만들기, where somebody whose
 * `GET /weddings` answers `[]` sits with no other exit — waiting on an invite,
 * or removed from a partner's wedding; and 수락, the only exit on a screen that
 * has none, which the "wrong Google account" recovery is built on (`#164`).
 * The last two are doors out of dead ends and are not duplicates of the first
 * (notes/2026-08-22-decision-logout-leaves-the-ledger.md).
 *
 * The wrapper's classes come from the caller because the screens hold it
 * differently — under the account it signs out of, under the form on a centered
 * column — and the message has to sit with the button either way.
 */
export function LogoutButton({ className }: { className: string }) {
  const logout = useLogout()

  return (
    <div className={className}>
      <button
        className={buttonClassName('secondary')}
        disabled={logout.isPending}
        onClick={() => logout.mutate()}
        type="button"
      >
        로그아웃
      </button>
      {logout.isError && (
        // Announced: the only other thing that changed is a button going back
        // to being pressable, which says nothing at all.
        <p className="text-body leading-body text-danger" role="alert">
          로그아웃하지 못했습니다. 연결을 확인하고 다시 눌러 주세요.
        </p>
      )}
    </div>
  )
}
