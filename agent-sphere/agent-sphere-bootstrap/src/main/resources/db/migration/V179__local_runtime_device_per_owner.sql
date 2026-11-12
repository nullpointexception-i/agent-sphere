-- A desktop install can be signed in by multiple AgentSphere accounts. The device key
-- identifies the physical install, so uniqueness must be scoped to the runtime owner:
-- a global unique index prevents any second account from binding the same machine.

DROP INDEX IF EXISTS uk_local_runtime_device;

CREATE UNIQUE INDEX IF NOT EXISTS uk_local_runtime_device_owner
    ON agent_local_runtime (owner_user_id, device_key)
    WHERE delete_flag = false;
