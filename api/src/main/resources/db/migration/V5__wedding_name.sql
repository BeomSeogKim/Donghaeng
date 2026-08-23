-- 결혼식 이름 — free text the couple writes, and the screen's title.
--
-- SAME RULES AS V1. Flyway applies this in the test suite and nowhere else; the
-- real database gets it typed by hand, wrapped in an explicit BEGIN; ... COMMIT;
-- (notes/2026-08-09-decision-schema-ownership.md). The width below is
-- load-bearing and `ddl-auto: validate` does not check it.
--
-- WHY FREE TEXT AND NOT A COMPOSITION of the two seats' names:
-- notes/2026-08-23-decision-the-wedding-has-a-name.md. v1 never records the
-- partner's name until they accept, so an auto-composed name would be half a
-- name for the whole span the couple uses the ledger most.

alter table wedding
    -- NULL until the couple types one, and NULLABLE FOREVER: it is optional at
    -- creation and `PATCH /weddings/{weddingId}` can clear it back to nothing.
    -- A wedding with no name is an ordinary wedding, and what the header renders
    -- in its place is the frontend's decision.
    --
    -- 100 matches wedding_party.name and guest.name, for the same reason those
    -- carry it: headroom for a phrase a person actually types, not for a URL.
    -- The application refuses a name with no VISIBLE character in it
    -- (`@WeddingName`); there is no CHECK here, exactly as wedding_party.name
    -- has none — the predicate walks Unicode code points and general categories,
    -- which is not a thing to transcribe into SQL twice.
    add column name varchar(100);
