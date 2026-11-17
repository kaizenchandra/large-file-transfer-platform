-- The floor is the last removed sequence; a cursor equal to it is still valid.
ALTER TABLE namespaces
    ADD COLUMN sync_floor bigint NOT NULL DEFAULT 0;
ALTER TABLE namespaces
    ADD CONSTRAINT sync_floor_bounds CHECK (sync_floor >= 0 AND sync_floor <= seq);
