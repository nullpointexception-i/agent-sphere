import { TIMING } from '../timing';

describe('timing constants', () => {
  it('exposes positive values for every timing knob', () => {
    for (const [key, value] of Object.entries(TIMING)) {
      expect(value).toBeGreaterThan(0);
      expect(Number.isFinite(value)).toBe(true);
    }
  });

  it('keeps ordering guarantees used by the UI', () => {
    expect(TIMING.SSE_RETRY_BASE_MS).toBeLessThan(TIMING.SSE_RECONNECT_MAX_MS);
    expect(TIMING.CLOCK_TICK_MS).toBeLessThanOrEqual(TIMING.SLOW_TOOL_MS);
    expect(TIMING.POLL_INTERVAL_MS).toBeLessThan(TIMING.STREAM_HARD_TIMEOUT_MS);
  });
});
