-- 동반인원이 하객 레코드가 된다. `expected_party_size` leaves; a guest points at
-- the guest who brought it.
--
-- SAME RULES AS V1. Flyway applies this in the test suite and nowhere else; the
-- real database gets it typed by hand, wrapped in an explicit BEGIN; ... COMMIT;
-- (notes/2026-08-09-decision-schema-ownership.md). **This one is not additive** —
-- it moves data and then drops a column, so applying half of it is a ledger with
-- duplicated parties in it. Type the whole file inside one transaction.
--
-- WHY, in short — the argument is
-- notes/2026-08-23-decision-companions-become-guests.md, which reverses §2 of
-- notes/2026-08-20-decision-guest-entry-side-and-companions.md.
--
-- A party of three was one row carrying a `3`. That made two rules free — a
-- companion takes the head's 측, and a head marked 불참 marks the companions 불참 —
-- because a number has no 측 and no attendance of its own. The founder has asked
-- for both of those to become movable, so the number becomes rows: **a party of
-- three is three 하객 records**, and the two rules become DEFAULTS APPLIED AT
-- CREATION rather than facts of the model.
--
-- Entry does not change. The couple still types 인원수; what changes is what the
-- number becomes.


-- ---------------------------------------------------------------------------
-- The reference. NULL means "this guest is the head of its own party", which is
-- every guest today and most guests always.
-- ---------------------------------------------------------------------------
alter table guest
    add column companion_of bigint;

-- COMPOSITE, to `guest (id, wedding_id)` — the shape guest_meal_count's FKs
-- already use, and for the same reason: a row joining one wedding's companion to
-- another wedding's head must be UNREPRESENTABLE rather than refused by a service
-- check somebody can forget. `ux_guest_id_wedding` exists in V1 as the target.
--
-- Note what this does NOT make wedding_id here: `guest.wedding_id` is still a ROOT
-- marker (the ledger is queried by wedding on every screen) and this FK does not
-- change that. The distinction api/AGENTS.md draws is about a column that exists
-- ONLY for integrity; this one has both jobs.
alter table guest
    add constraint fk_guest_companion
        foreign key (companion_of, wedding_id) references guest (id, wedding_id);

-- A guest cannot bring itself. The deeper rule — a companion may not itself have
-- companions — is the service's, because no row-level CHECK can read the parent's
-- own companion_of; the only path that writes this column is the create, and it
-- only ever points at a head.
alter table guest
    add constraint ck_guest_companion_not_self
        check (companion_of is null or companion_of <> id);

-- The ledger read folds by party, so every read of a wedding's guests looks this
-- column up. Partial for the reason ix_guest_wedding is: no screen shows a deleted
-- 하객.
create index ix_guest_companion
    on guest (companion_of)
    where deleted_at is null;


-- ---------------------------------------------------------------------------
-- The expansion. Every live guest with `expected_party_size = N` becomes N rows:
-- itself, plus N-1 companions.
--
-- WHAT THE GENERATED ROWS INHERIT, and why each one:
--   name              — `{대표자 이름} 동반 N`, N from 1. Given ONCE and never
--                       regenerated: renaming the head later does not rename these,
--                       and the couple may type over them (the founder's rule).
--                       `left(...)` keeps the result inside varchar(100) for a head
--                       whose own name is already at the bound.
--   side              — the head's. The rule that used to be free, applied once.
--   attendance        — the head's, for the same reason. After this, each row moves
--                       on its own; that is the whole point of the change.
--   group             — the head's category AND label, because the party's people
--                       were already inside the head's group in every aggregation
--                       this ledger has ever answered. Landing them in 기타 would
--                       silently move a couple's group breakdown.
--   created_at / _by  — the head's, so the ledger's entry order is unchanged and a
--                       companion sorts immediately after the person who brought it.
--   contact, note     — NOT inherited. A phone number belongs to a person and a
--                       배려사항 is about a body; copying either would be inventing
--                       a fact about somebody we have never been told anything about.
--
-- Soft-deleted heads are skipped. Expanding one would create LIVE companions of a
-- 하객 the couple removed, and the party size of a deleted row is not a number this
-- product owes anybody.
-- ---------------------------------------------------------------------------
insert into guest (wedding_id, name, side, group_category, group_label,
                   expected_attending, expected_party_size, companion_of,
                   created_by, created_at, updated_by, updated_at)
select g.wedding_id,
       left(g.name, 100 - char_length(' 동반 ' || companion.n)) || ' 동반 ' || companion.n,
       g.side,
       g.group_category,
       g.group_label,
       g.expected_attending,
       1,
       g.id,
       g.created_by,
       g.created_at,
       g.updated_by,
       g.updated_at
from guest g
    cross join lateral generate_series(1, g.expected_party_size - 1) as companion(n)
where g.deleted_at is null
  and g.expected_party_size > 1;


-- ---------------------------------------------------------------------------
-- And the column goes, taking ck_guest_expected_party_size with it. 식대 인원 is
-- now the COUNT of attending guest records, not a sum of party sizes — the same
-- number for every party that agrees with itself, and a truer one for every party
-- that does not.
--
-- `confirmed_party_size` is deliberately LEFT IN PLACE, unused and inert. Nothing
-- in v1 writes it (notes/2026-08-21-decision-attendance-is-two-states.md), and it
-- belongs to the confirmed-slot model this change was not asked to re-decide. It
-- is dead weight rather than a trap: no query reads it, and `GuestResponse`
-- publishes neither confirmed slot.
-- ---------------------------------------------------------------------------
alter table guest
    drop column expected_party_size;
