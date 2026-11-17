-- Quarantine is unpublished physical storage, separately visible in the owner's usage budget.
ALTER TABLE namespaces
    ADD COLUMN quarantined bigint NOT NULL DEFAULT 0 CHECK (quarantined >= 0);
UPDATE namespaces n
SET quarantined=coalesce((SELECT sum(u.size) FROM upload_sessions u WHERE u.ns = n.id AND u.state = 'QUARANTINED'), 0);
-- Older installations may already exceed the budget after backfill. Admission then fails until cleanup,
-- instead of rejecting the migration or silently discarding quarantined evidence.
