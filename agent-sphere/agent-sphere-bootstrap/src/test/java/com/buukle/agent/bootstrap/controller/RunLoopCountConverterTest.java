package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.service.converter.RunConverter;
import com.buukle.agent.instance.domain.AgentRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * loopCount must round-trip fully between VO ↔ DO.
 *
 * <p>This is the premise for "suspension no longer resets the loop budget": on resume, SessionRunner starts from
 * {@code currentRun.getLoopCount()}, a value read back from the DB row through the converter.
 * If the converter misses the mapping, the field stays null and the loop budget silently falls back to "start from 0 on every call" —
 * no error, just a dead guard, which is exactly the kind of regression that must be tested.
 */
class RunLoopCountConverterTest {

    private final RunConverter converter = new RunConverter();

    private static AgentRun row(Integer loopCount) {
        AgentRun run = new AgentRun();
        run.setId(1L);
        run.setSessionId(2L);
        run.setStatus("RUNNING");
        run.setLoopCount(loopCount);
        return run;
    }

    @Test
    void loopCount_survivesRowToVo() {
        RunVO vo = converter.toVO(row(7));
        assertEquals(7, vo.getLoopCount());
    }

    @Test
    void loopCount_survivesVoToRow() {
        RunVO vo = new RunVO();
        vo.setId(1L);
        vo.setLoopCount(7);
        AgentRun entity = converter.toDO(vo);
        assertEquals(7, entity.getLoopCount());
    }

    @Test
    void loopCount_roundTripsWithoutLoss() {
        AgentRun back = converter.toDO(converter.toVO(row(42)));
        assertEquals(42, back.getLoopCount());
    }

    @Test
    void nullLoopCount_staysNullForLegacyRows() {
        // Rows predating V82 have no value; null must be preserved, not silently given semantics other than 0
        assertNull(converter.toVO(row(null)).getLoopCount());
    }
}