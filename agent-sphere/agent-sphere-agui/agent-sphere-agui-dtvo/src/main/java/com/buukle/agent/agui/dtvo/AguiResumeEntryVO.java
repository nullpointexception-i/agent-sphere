package com.buukle.agent.agui.dtvo;

import lombok.Data;

import java.io.Serializable;

/**
 * AG-UI {@code ResumeEntry}（对应 @ag-ui/core ResumeEntrySchema）：
 * 澄清（interrupt）应答时由客户端随 run 请求带回，用于续跑被暂停的 run。
 */
@Data
public class AguiResumeEntryVO implements Serializable {

    private String interruptId;

    /** resolved | cancelled */
    private String status;

    /** 用户应答内容（字符串），与主站澄清响应一致 */
    private Object payload;
}
