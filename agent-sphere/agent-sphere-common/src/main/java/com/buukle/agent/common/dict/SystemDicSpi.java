package com.buukle.agent.common.dict;

import java.util.List;

/** Read access to global system dictionaries (implemented by infrastructure). */
public interface SystemDicSpi {

    /** Active entries of the given type, ordered by sort number. */
    List<SystemDicEntry> listActive(String dicType);

    /** Active entry by type and code, or {@code null} when missing/disabled. */
    SystemDicEntry findActive(String dicType, String code);
}
