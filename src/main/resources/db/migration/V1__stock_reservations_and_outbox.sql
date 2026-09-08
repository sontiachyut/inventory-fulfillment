CREATE TABLE stock (
    tenant_id varchar(64) NOT NULL,
    sku varchar(64) NOT NULL,
    initial_qty integer NOT NULL CHECK (initial_qty BETWEEN 1 AND 1000000),
    available_qty integer NOT NULL CHECK (available_qty >= 0),
    reserved_qty integer NOT NULL CHECK (reserved_qty >= 0),
    sold_qty integer NOT NULL CHECK (sold_qty >= 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    PRIMARY KEY (tenant_id,sku),
    CHECK (initial_qty::bigint = available_qty::bigint + reserved_qty::bigint + sold_qty::bigint)
);
CREATE TABLE reservation (
    id uuid PRIMARY KEY,
    tenant_id varchar(64) NOT NULL,
    sku varchar(64) NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 1000000),
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE','CONFIRMED','CANCELLED','EXPIRED')),
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,sku) REFERENCES stock
);
CREATE INDEX active_expiry ON reservation(expires_at,id) WHERE state='ACTIVE';
CREATE TABLE idempotency (
    tenant_id varchar(64) NOT NULL,
    operation varchar(32) NOT NULL CHECK (operation='reserve'),
    request_key varchar(64) NOT NULL,
    request_hash char(64) NOT NULL,
    reservation_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id,operation,request_key),
    FOREIGN KEY (tenant_id,reservation_id) REFERENCES reservation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE outbox (
    event_id uuid PRIMARY KEY,
    event_type text NOT NULL,
    tenant_id varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL REFERENCES reservation(id),
    aggregate_version bigint NOT NULL CHECK (aggregate_version > 0),
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version=1),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    UNIQUE (aggregate_id,aggregate_version)
);
CREATE INDEX outbox_unpublished ON outbox(created_at,event_id) WHERE published_at IS NULL;
