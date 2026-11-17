-- Durable responses make offline client metadata retries safe after response loss.
CREATE TABLE client_operations
(
    ns            uuid        NOT NULL REFERENCES namespaces,
    actor         text        NOT NULL,
    operation_key text        NOT NULL,
    fingerprint   text        NOT NULL,
    response      jsonb       NOT NULL,
    created       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (ns, actor, operation_key)
);
