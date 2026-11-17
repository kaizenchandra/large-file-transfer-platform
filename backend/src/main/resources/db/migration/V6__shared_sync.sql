-- Recipient streams are isolated from the owner's log, including its historical paths.
CREATE TABLE sync_subscriptions
(
    id      uuid PRIMARY KEY,
    ns      uuid   NOT NULL REFERENCES namespaces,
    root    uuid   NOT NULL REFERENCES file_nodes,
    subject text   NOT NULL,
    seq     bigint NOT NULL DEFAULT 0,
    UNIQUE (ns, root, subject)
);
CREATE TABLE sync_projection
(
    subscription uuid  NOT NULL REFERENCES sync_subscriptions,
    node         uuid  NOT NULL,
    payload      jsonb NOT NULL,
    PRIMARY KEY (subscription, node)
);
CREATE TABLE shared_changes
(
    subscription uuid        NOT NULL REFERENCES sync_subscriptions,
    seq          bigint      NOT NULL,
    payload      jsonb       NOT NULL,
    action       text        NOT NULL,
    created      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subscription, seq)
);
