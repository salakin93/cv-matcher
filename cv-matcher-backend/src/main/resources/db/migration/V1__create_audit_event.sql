CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    actor_type VARCHAR(32) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID NOT NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_event_entity ON audit_event (entity_type, entity_id);
CREATE INDEX idx_audit_event_created_at ON audit_event (created_at);
