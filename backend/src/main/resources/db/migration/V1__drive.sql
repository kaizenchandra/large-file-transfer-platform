CREATE TABLE namespaces
(
    id       uuid PRIMARY KEY,
    tenant   text    NOT NULL,
    subject  text    NOT NULL,
    active   boolean NOT NULL DEFAULT true,
    quota    bigint  NOT NULL DEFAULT 107374182400 CHECK (quota >= 0),
    used     bigint  NOT NULL DEFAULT 0 CHECK (used >= 0),
    reserved bigint  NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    seq      bigint  NOT NULL DEFAULT 0,
    UNIQUE (tenant, subject),
    CHECK (used + reserved <= quota)
);
CREATE TABLE file_nodes
(
    id              uuid PRIMARY KEY,
    ns              uuid        NOT NULL REFERENCES namespaces,
    parent          uuid REFERENCES file_nodes,
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 255),
    kind            text        NOT NULL CHECK (kind IN ('FILE', 'FOLDER')),
    revision        bigint      NOT NULL DEFAULT 1,
    current_version uuid,
    deleted         boolean     NOT NULL DEFAULT false,
    updated         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ns, id),
    FOREIGN KEY (ns, parent) REFERENCES file_nodes (ns, id)
);
CREATE UNIQUE INDEX node_names ON file_nodes (ns, coalesce(parent,'00000000-0000-0000-0000-000000000000'::uuid), lower(name)) WHERE NOT deleted;
CREATE INDEX node_parent ON file_nodes (ns, parent, id);
CREATE INDEX node_search ON file_nodes (ns, lower(name) text_pattern_ops);
CREATE TABLE file_versions
(
    id         uuid PRIMARY KEY,
    node       uuid   NOT NULL REFERENCES file_nodes,
    object_key text   NOT NULL,
    size       bigint NOT NULL CHECK (size >0
) , sha256 text NOT NULL, created timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE file_nodes
    ADD CONSTRAINT current_version_fk FOREIGN KEY (current_version) REFERENCES file_versions DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE upload_sessions
(
    id          uuid PRIMARY KEY,
    ns          uuid   NOT NULL REFERENCES namespaces,
    actor       text   NOT NULL,
    idem        text   NOT NULL,
    fingerprint text   NOT NULL,
    node        uuid   NOT NULL,
    parent      uuid,
    name        text   NOT NULL,
    expected    bigint NOT NULL,
    size        bigint NOT NULL CHECK (size >0
) , sha256 text NOT NULL,
 part_size bigint NOT NULL, object_key text NOT NULL UNIQUE, multipart text,
 state text NOT NULL CHECK(state IN ('INITIATED','UPLOADING','COMPLETING','COMPLETED','ABORTED')),
 expires timestamptz NOT NULL DEFAULT now()+interval '24 hours', UNIQUE(ns,actor,idem)
);
CREATE TABLE upload_parts
(
    upload uuid REFERENCES upload_sessions,
    number integer CHECK (number BETWEEN 1 AND 10000),
    sha256 text NOT NULL,
    PRIMARY KEY (upload, number)
);
CREATE TABLE sharing_permissions
(
    id      uuid PRIMARY KEY,
    node    uuid NOT NULL REFERENCES file_nodes,
    subject text NOT NULL,
    role    text NOT NULL CHECK (role IN ('VIEWER', 'EDITOR')),
    UNIQUE (node, subject)
);
CREATE TABLE share_links
(
    id         uuid PRIMARY KEY,
    node       uuid        NOT NULL REFERENCES file_nodes,
    token_hash text        NOT NULL UNIQUE,
    expires    timestamptz NOT NULL,
    revoked    boolean     NOT NULL DEFAULT false
);
CREATE TABLE change_log
(
    ns      uuid REFERENCES namespaces,
    seq     bigint      NOT NULL,
    node    uuid        NOT NULL,
    payload jsonb       NOT NULL,
    created timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (ns, seq)
);
CREATE TABLE devices
(
    id      uuid PRIMARY KEY,
    ns      uuid        NOT NULL REFERENCES namespaces,
    subject text        NOT NULL,
    name    text        NOT NULL,
    ack     bigint      NOT NULL DEFAULT 0,
    seen    timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE audit_records
(
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ns      uuid        NOT NULL,
    actor   text        NOT NULL,
    action  text        NOT NULL,
    entity  uuid        NOT NULL,
    created timestamptz NOT NULL DEFAULT now()
);
-- Published rows cannot be edited in place. Retention deletes must be reference-aware.
CREATE FUNCTION immutable_version() RETURNS trigger
    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable version';
END $$;
CREATE TRIGGER immutable_version
    BEFORE UPDATE
    ON file_versions
    FOR EACH ROW EXECUTE FUNCTION immutable_version();
