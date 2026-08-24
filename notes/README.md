# notes/ — decision records

**`notes/` is *why*.** `AGENTS.md` carries the rule that binds today; an
Issue carries what's left. A record is written once and then only amended
with a banner — it is never quietly rewritten.

**Read newest-first.** The 2026-08-06 records supersede parts of nearly every
earlier note — including each other — and every affected note carries a banner
saying what changed.

**Adding one**: `YYYY-MM-DD-{decision,design,review}-<slug>.md`. A
`decision` settles something; a `design` works a problem through; a `review`
examines what already exists. Link it from this index in the same change, and
reflect it in the right `AGENTS.md` **only if it changes a standing rule** —
see that file's `Rules` section for the admission test.

## Product & scope

| Record | What it settles |
|---|---|
| `2026-08-23-decision-the-customer-word-is-결혼식.md` | 고객 화면은 결혼식, 코드·주석·기록은 웨딩 — 웨딩은 업자의 말이다; 화면의 말과 코드의 말이 갈리는 두 번째 쌍이라 규칙으로 적는다 (원장/하객 명부, 웨딩/결혼식) |
| `2026-08-23-decision-companions-become-guests.md` | 동반인원은 같은 `하객` 레코드다 — `2026-08-20` §2를 뒤집는다; 가상 이름은 주어지고 고쳐지며 재생성되지 않는다; 원장은 팀 단위로 접히고 섞인 팀은 `3 / 4`로 읽힌다; 식대 인원은 참석 하객 레코드의 개수가 된다 |
| `2026-08-23-decision-the-wedding-has-a-name.md` | 결혼식 이름은 커플이 쓰는 자유 입력이다 — 자동 조립은 파트너가 오기 전까지 반쪽 이름이라 거절됐다; 이름이 생기면서 화면의 "원장"은 설 자리를 잃고 "하객 명부"가 된다 |
| `2026-08-22-decision-the-couples-two-seats.md` | 웨딩엔 자리가 둘 — `membership`과 이름 두 컬럼은 한 개념이었다; 초대는 빈 자리를 채운다; 부모·플래너는 지원자지 주체가 아니다 |
| `2026-08-22-decision-entitlement-belongs-to-the-wedding.md` | 권리는 웨딩에, 결제 주체만 사람에; 구독 행은 상태가 아니라 텀이다; 웨딩당 활성 1건은 인덱스가 지킨다 |
| `2026-08-22-decision-the-invite-link.md` | 초대 링크는 하루 살고 재발급된다; 토큰은 URL 프래그먼트로만 다니고 서버 로그에 남지 않는다; `sessionStorage`가 OAuth 왕복을 건너므로 `returnTo`를 만들지 않는다 |
| `2026-08-22-decision-the-invite-links-residuals.md` | 카톡 인앱은 안전하게 실패하지 않는다 — 구글이 임베디드 웹뷰를 거부하므로 아예 안 돌아온다; 로그아웃 후에도 탭에 남는 자격증명·클립보드·적대적 인바운드 프래그먼트 |
| `2026-08-22-decision-the-audit-trail-waits.md` | v1은 변경 주체를 기록하지 않는다 (`#25`·`#179` post-v1); `guest_change`는 테이블만 있고 아무것도 쓰지 않았다 — 세 문서가 기댄 전제가 거짓이었다; 런칭 기간은 영원히 귀속 불가 |
| `2026-08-22-decision-deploy-comes-after-feature-complete.md` | 배포는 기능이 대강 끝난 뒤에; 도메인은 처음부터 블로커가 아니었다 — 레지스트리·서버·Pages 프로젝트가 없는 것이었다; 첫 배포가 런칭에 붙는 값 |
| `2026-08-21-decision-two-accounts-and-the-v1-recut.md` | 커플은 두 계정이고 한 사람은 웨딩 하나에만 속한다; 초대는 링크; 유아식은 `#9`와 맞바꿔 `post-v1`로 |
| `2026-08-21-decision-attendance-is-two-states.md` | 참석 여부는 참석·불참 둘뿐 — 두 번째 슬롯과 함께 "아직 모르는 N명"도 내려간다 |
| `2026-08-19-decision-launch-date-and-google-only.md` | **Launch is 2026-08-31.** v1 ships on Google alone; the "does this block v1?" gate |
| `2026-08-11-decision-deletion-and-infant-meals.md` | 유아식 does not adjust 보증인원; deletion cannot trigger `GuestChange` retention |
| `2026-08-11-decision-import-row-rejection.md` | A bad 관계 rejects the row, not the file — supersedes the synonym table |
| `2026-08-06-decision-v1-scope-and-meals.md` | The v1 cut; meal types per Wedding |
| `2026-08-06-decision-drop-response-model.md` | No `RsvpResponse` / `ResponseMatch` in v1 |
| `2026-08-06-design-ledger-and-import.md` | Ledger + headcount are one screen; the import workflow |
| `2026-08-06-review-scale-and-extensibility.md` | What the v1 shape costs later |
| `2026-08-05-design-meal-headcount.md` | Headcount aggregation |
| `2026-07-30-design-guest-ledger-hard-spots.md` | The MVP's hard design spots |
| `2026-07-26-decision-core-scope.md` | Core scope |
| `2026-07-26-mvp-v1-requirements.md` | The v1 requirement list |
| `2026-07-26-founder-interview.md` | Origin — the problem in the founder's words |

## Domain & data

| Record | What it settles |
|---|---|
| `2026-08-11-decision-baseline-schema-calls.md` | The v1 baseline schema's contested calls; verified-email merge key; integrity `wedding_id` |
| `2026-08-10-decision-soft-delete.md` | Every user-deletable row is soft-deleted |
| `2026-08-07-decision-import-idempotency.md` | File hash; a returning file is a stale name list |
| `2026-08-03-design-domain-model.md` | The domain model |

## Backend (`api/`)

| Record | What it settles |
|---|---|
| `2026-08-22-decision-the-superseded-link-speaks.md` | 재발급에 밀린 토큰은 `INVITE_SUPERSEDED`를 받는다 — verifier를 맞힌 사람에게만 하는 말이라 추측자는 아무것도 못 배운다; 소비된 토큰과 경쟁에 진 토큰은 계속 `INVITE_NOT_FOUND` |
| `2026-08-22-decision-v1-ships-no-app-rate-limit.md` | v1은 앱 레이트 리밋을 싣지 않는다 — 남은 표면이 전부 세션 뒤 한 웨딩 범위이고, 초대 수락은 카운터가 아니라 256비트 엔트로피가 지킨다; 뒤집는 트리거 셋 |
| `2026-08-22-decision-the-seat-name-edit.md` | 필수 단일 필드는 `PATCH`가 아니라 `PUT`이다; 빈 자리는 미리 채울 수 없고 주소 자체가 없다; 숫자가 움직일 수 없는 뮤테이션은 집계를 싣지 않고 `wedding/`에 남는다; **보이지 않는 문자로만 된 이름은 이름으로 치지 않는다** — 제품의 모든 이름 필드에 걸린다 |
| `2026-08-22-decision-the-partner-invite.md` | 초대 토큰은 `selector.verifier`로 해시 저장된다 — 덤프로는 살아 있는 링크가 나오지 않는다; 초대 행은 자리를 가리키고 `wedding_id`를 갖지 않는다; 수락은 세 번째 비스코프 엔드포인트고, 자리 행 잠금 하나가 경쟁 넷을 다 막는다; 토큰은 딱 한 번만 보인다 |
| `2026-08-22-decision-partial-update-shape.md` | 생략은 안 건드림, `null`은 지움; 보증인원은 다시 비울 수 있다; 집계를 싣는 뮤테이션은 `guest/`에서 조립된다; `WeddingResponse`는 보증인원을 싣지 않는다 |
| `2026-08-21-decision-one-wedding-per-person.md` | 두 번째 웨딩은 409 `ALREADY_IN_A_WEDDING`; 동시 요청은 자문 잠금이 막는다(유니크 인덱스는 대표의 DDL); `GET /weddings`는 배열로 남되 원소는 많아야 하나 |
| `2026-08-21-decision-the-headcount-endpoint.md` | 인원수는 참석을 먼저 읽고 원장 필터와 같은 식을 쓴다; 보증인원은 비었으면 멤버가 없다; 집계는 `guest/`에서 계산한다 |
| `2026-08-20-decision-the-ledger-read-and-its-filters.md` | 원장 목록은 페이지를 나누지 않는다; 입력 순서가 계약이고, 참석 필터는 인원수가 세는 값을 읽는다 |
| `2026-08-20-decision-row-concurrency-and-the-audit-trail.md` | 같은 컬럼 경쟁은 마지막 쓰기가 이기되 덮인 값은 `guest_change`에 남는다; `@Version` 없음, 상대적 변경 금지 |
| `2026-08-20-decision-listing-the-callers-weddings.md` | `GET /weddings` answers 최초 1회 from the session; a scopeless wedding endpoint is named in an allowlist, never inferred from the path |
| `2026-08-19-decision-wedding-scope-gate.md` | The gate fails closed by a build-time sweep, not an interceptor; resolution filters `wedding.deleted_at`; a refusal is marked in the log only |
| `2026-08-13-decision-first-login-idempotency-and-email-merge.md` | First login is idempotent per identity; only a verified address merges, and we can be the verifier |
| `2026-08-13-decision-login-failure-return-path.md` | A failed callback returns to the front with a closed code in the fragment; the copy is `web/`'s |
| `2026-08-12-decision-login-slice-by-provider.md` | Login splits by provider; the email merge stays with Google |
| `2026-08-17-decision-first-domain-endpoint-shape.md` | The pattern fifteen endpoints copy; where the `CurrentWedding` resolver may live; a validation bound is the column's |
| `2026-08-17-decision-log-masking-mechanism.md` | Masking is the driver's switch and four pinned loggers, not a message scrubber — and a cast is not a validator |
| `2026-08-17-decision-pre-auth-rate-limit-and-session-retention.md` | The pre-auth limit is the edge's and names both OAuth paths; a dead session row keeps 90 days past expiry |
| `2026-08-12-decision-session-token-shape.md` | The session token is `selector.verifier` — for a testable comparison, not a closed hole |
| `2026-08-12-decision-cors.md` | CORS denies by default, exact origins only, patterns forbidden at the type level |
| `2026-08-12-decision-session-lifetimes.md` | Session idle 30 days, absolute 180 — the couple open this a few times a month |
| `2026-08-12-decision-session-cookie-ambiguity.md` | Two session cookies: refuse the read, revoke both |
| `2026-08-12-decision-auth-package-structure.md` | `auth/` splits into account · oauth · session; the boundary and the layer direction are tests, not `internal` |
| `2026-08-10-decision-auth-gate-and-sequence.md` | The resolver is the gate, not the filter chain; the build order |
| `2026-08-10-decision-cross-tenant-status-code.md` | Cross-tenant is 404, never 403 |
| `2026-08-09-decision-schema-ownership.md` | Flyway in tests only; DDL applied by hand |
| `2026-08-07-decision-backend-api-conventions.md` | No envelope; RFC 9457 + `code`; DTO naming |
| `2026-08-07-decision-backend-architecture.md` | Domain-based packages; shallow layers |
| `2026-08-07-decision-backend-tdd-methodology.md` | Red / Blue / Green, per requirement |

## Frontend (`web/`) & design

| Record | What it settles |
|---|---|
| `2026-08-23-decision-the-form-language.md` | 색과 의미는 시스템이었고 형태는 무드보드였다 — 토큰이 강제한 건 `8 / 1 / 999`뿐이었다; radius 0 · 알약 없음 · 가로 행선 없음 · 세로 괘선 · 목소리 셋; 금박은 인장·구연·기준선 셋만 하고 면적을 갖는다 |
| `2026-08-22-decision-the-sheet-carries-both.md` | 하객 추가 시트는 측과 그룹을 둘 다 이월한다 — 비대칭이 유일하게 변호 불가능했고, 부모님 명단은 블록으로 온다; `#12`가 v1이라는 전제가 하중을 받는다 |
| `2026-08-22-decision-the-409-recovery-loop.md` | 409를 두 번째로 만나면 화면이 말한다 — 옳은 수정 둘이 만든 고리라 어느 쪽도 되돌리지 않는다; 표시는 로그아웃과 함께 죽고 토큰은 건드리지 않는다 |
| `2026-08-22-decision-logout-leaves-the-ledger.md` | 로그아웃이 원장 고정 헤더를 떠나 마이페이지로 간다 (`#195`를 `#159`에 흡수); 수락 화면과 웨딩 만들기의 로그아웃은 출구라서 남는다 |
| `2026-08-22-decision-guest-add-sheet.md` | 하객 추가는 원장 위의 시트다; 한 명 넣어도 닫히지 않는다 (이월은 `-the-sheet-carries-both`가 고쳐 씀); 숫자는 응답에서, 목록은 무효화에서 |
| `2026-08-21-decision-ledger-screen.md` | 원장 is home; 이름 가나다순 is the client's order; one filter value per axis; the ledger's query keys |
| `2026-08-21-decision-the-headcount-on-screen.md` | 세지 않은 숫자는 0으로 그리지 않는다; 보증인원이 없으면 화면은 그 얘기를 하지 않는다; 뮤테이션 숫자는 `setHeadcount`로 들어온다 |
| `2026-08-21-decision-query-defaults-and-mutation-ordering.md` | The QueryClient's defaults; out-of-order mutation responses are prevented by serialising, not reconciled |
| `2026-08-19-decision-generated-api-types.md` | The generated types are committed; a `seam` CI job refuses a stale one; what stays hand-written |
| `2026-08-13-decision-frontend-routing.md` | Plain `<Routes>`, no loaders; the session gate sits above the table |
| `2026-08-10-decision-design-value-enforcement.md` | Our own checker, not the linter |
| `2026-08-08-decision-frontend-architecture.md` | Flat until it scatters; React Query; the token bridge |
| `2026-08-08-decision-frontend-testing-methodology.md` | Integration-first; where tests are mandatory |
| `2026-08-07-design-system.md` | 백자 · 금박 / 나전칠기; tokens, faces, the component inventory |
| `2026-08-07-design-screens-and-flow.md` | The screens and the flow between them |

## Process & infrastructure

| Record | What it settles |
|---|---|
| `2026-08-22-decision-the-envelope-rule-narrows.md` | 봉투는 인원수에 참여하는 리소스의 것이다 — 이음매 가산성이 지키는 건 언젠가 숫자를 실을 응답이므로; 서술이 아니라 열거로 적어야 안 흔들린다 |
| `2026-08-13-decision-drop-the-stop-pipeline.md` | **No development ritual.** What was removed, and what it cost |
| `2026-08-13-decision-static-front-and-content-type-gate.md` | The front ships static; no endpoint accepts a CORS-safelisted content type, and CI sweeps for it |
| `2026-08-19-decision-openapi-artifact.md` | The seam's document is a test artifact at `api/build/openapi.json`; a test-source controller is a `@TestComponent` or it is published |
| `2026-08-08-decision-development-tempo.md` | *Superseded* — vertical slices; one stop; the comprehension gate |
| `2026-08-08-decision-work-tracking.md` | GitHub Issues via `gh`; two milestones, four labels |
| `2026-08-20-decision-guest-entry-side-and-companions.md` | *§2 superseded* — 측은 기본값을 갖지 않는다; 동반인원 §2는 `2026-08-23`이 뒤집었다 |
| `2026-08-20-decision-mutation-response-envelope.md` | 뮤테이션 응답은 `{resource, headcount}` — 봉투 금지 규칙이 좁혀진다 |
| `2026-08-20-decision-merge-order-gate.md` | 두 PR이 따로는 초록, 합치면 빨강 — 무엇을 사고 무엇을 안 샀나 |
| `2026-08-24-decision-the-agent-merges-behind-a-gate.md` | The agent merges on a green check; the gate repairs that had to come first |
| `2026-08-08-decision-build-workflow.md` | *Partly superseded* — the CI jobs; never merge a red check |
| `2026-08-11-decision-claude-setup.md` | Where a behaviour lives — skill vs hook vs prose; the prompt cleanup |
| `2026-08-11-decision-agents-md-hierarchy.md` | AGENTS.md becomes a hierarchy, and gains an eviction rule |
| `2026-08-08-decision-merge-gate.md` | The local gate, and why it is leaky on purpose |
| `2026-08-08-decision-scaffold-secrets-and-surface.md` | A secret never travels in a connection string |
| `2026-07-30-decision-network-security.md` | The security posture |
| `2026-07-30-decision-tech-stack.md` | The stack |
| `2026-07-30-decision-client-strategy.md` | Token from the request; all computation server-side |
