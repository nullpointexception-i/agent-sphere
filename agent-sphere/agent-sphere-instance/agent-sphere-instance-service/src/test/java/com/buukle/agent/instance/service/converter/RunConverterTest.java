package com.buukle.agent.instance.service.converter;

import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V100 regression: the no-clarification flag must survive a DB round trip.
 *
 * <p>It used to live only on the in-memory RunVO. After an approval/wait resume the run
 * was rebuilt from the database without it, so ContextPreparer stopped registering the
 * finish tool for STRICT_FINISH worker runs and they looped until stopped manually.
 */
class RunConverterTest {

    private final RunConverter converter = new RunConverter();

    @Test
    void noClarificationIsPersistedWhenTrue() {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setUserMessage("worker goal");
        dto.setNoClarification(true);

        assertEquals(Boolean.TRUE, converter.toDO(dto).getNoClarification());
    }

    @Test
    void noClarificationDefaultsToFalseWhenAbsent() {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setUserMessage("chat message");

        assertEquals(Boolean.FALSE, converter.toDO(dto).getNoClarification());
    }

    @Test
    void toVoReadsPersistedNoClarification() {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setUserMessage("worker goal");
        dto.setNoClarification(true);

        RunVO vo = converter.toVO(converter.toDO(dto));

        assertEquals(Boolean.TRUE, vo.getNoClarification());
    }

    @Test
    void nullNoClarificationIsNotWipedOnUpdate() {
        RunVO vo = new RunVO();

        // null must stay null so MyBatis-Plus skips the column instead of writing false.
        assertNull(converter.toDO(vo).getNoClarification());
    }
}
