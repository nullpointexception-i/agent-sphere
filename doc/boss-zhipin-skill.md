# BOSS 直聘 搜索-筛选-收藏 Skill操作经验

> 目标：在 `https://www.zhipin.com/web/chat/search` 页面内（**不离开该页**）完成 候选人搜索 → 条件筛选 → 读取评估 → 高匹配收藏 的完整流程，并严格遵守节奏与风控。
>
> ⚠️ **强制规则：严禁使用 iframe 带参 URL 直达筛选；所有操作必须都在 /web/chat/search 父页面内完成**（筛选控件位于内嵌搜索 iframe 中，一律用 executeJS + 事件派发操作，不修改其 src；也不得离开本页单独打开搜索 iframe 页）。
>
> 本技能只沉淀「操作经验」：流程、步骤、页面结构、事件方式与风控节奏；不包含任何业务具体值（薪资区间、城市码、经验年限、关键词等均按每次需求配置）。

---

## 一、页面结构（先观察再操作）

- **父页面**：搜索页 = 左侧导航（职位管理/沟通/搜索/意向沟通等）+ 内嵌搜索 iframe。
- **搜索 iframe**：`iframe[name=searchFrame]`，src 形如 `/web/frame/search/?jobId=&keywords=&t=&source=&city=`。所有搜索/筛选/候选人卡片都在此 iframe 内。
- **简历详情弹层**：点击候选人卡片后，在**父页面**出现 `.boss-dialog__wrapper`（内部含简历 iframe），**收藏按钮在弹层内**。
- **验证占位页**：页面可能只显示隐藏字段 token/x/y/w/h 与 `#saveCrop` 保存按钮，说明处于拼图验证/风控占位态；随后可能弹出 `.crop_wrap`（添加公司照片/验证裁剪弹窗）。

## 二、启动与恢复

1. 导航到搜索页，等待 10–60 秒。该站加载慢，wait 单次上限 10s，需**多次 wait**；中途可能 timeout，属正常，继续 wait 即可。
2. 若出现 `.crop_wrap` 弹窗：用 executeJS 点击其 `.cancle` 关闭；若仍显示验证表单，按“验证码处理”规则等待人工。
3. 确认 `iframe[name=searchFrame]` 存在且候选人卡片数量 > 0 后再继续。

## 三、设置筛选（全程留在 /web/chat/search 父页面，对 iframe 内元素用 executeJS + dispatchEvent）

> ⚠️ **强制规则：严禁使用 iframe 带参 URL**（如给 `iframe[name=searchFrame]` 设 `src` 带 `keywords/city` 直达筛选）。**所有操作必须都在 `https://www.zhipin.com/web/chat/search` 父页面内完成**，不得通过修改 iframe url 跳转、也不得离开本页。

- **关键词**：在搜索 iframe 内的搜索输入框中输入目标关键词并提交（全部用事件派发，见下方模板）。
- **城市**：在城市位置处展开下拉并点击目标城市（不通过 URL 参数指定）。
- 其余筛选（点击后等 3–5s 再验证，筛选计数异步更新）：
  - **学历**、**经验**：按需求在各自下拉/选项区域内点击对应文本项。
  - **薪资**：先打开双下拉，低/高两列分别选“下限 / 不限（或上限）”，随后验证显示符合预期。
  - **求职状态**：展开并依次多选目标状态项（如多个“在职-*”），完成后验证容器文本已变为多项组合。
  - **行业**：若 UI 无独立行业筛选，需按候选人**当前公司归属**逐人评估。
- **事件派发模板**（iframe 内元素一律用它，不要用浏览器 click，也不要直接改 iframe 的 src）：

```js
['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){
  try{ el.dispatchEvent(new PointerEvent(t,{bubbles:true,cancelable:true,composed:true,view:iframe.contentWindow,clientX:x,clientY:y})); }
  catch(e){ el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:iframe.contentWindow})); }
});
```

## 四、读取与评估

1. 用 executeJS 一次性读取 iframe 内所有候选人卡片的 textContent，提取：姓名/活跃度/年龄/年限/学历/求职状态/期望薪资/技能摘要/期望城市/职位/当前公司/院校等（卡片内含标识数据的属性）。
2. **匹配评估**（多维度综合评价）：
   - 硬性门槛：学历、经验年限、技能栈（如“目标栈 + AI”方向）；
   - 加分项：头部背景（各行业公认头部企业）、期望薪资与预算接近、在职状态、稳定性；
   - **排除项**：外包/测试/运维/跳槽频繁等。
   - 达到配置的匹配阈值（如 ≥75%）才算匹配。

## 五、打开详情与收藏（关键操作）

> 切勿直接点击卡片链接（其 href 为 javascript:; 且点击会新开空白标签，属 JS/风控行为）。

1. 用 executeJS 对 iframe 内候选人卡片对应的打开入口，**派发完整事件序列** → 父页面出现 `.boss-dialog__wrapper` 弹层。
2. 等待 4–6s 让弹层渲染。
3. **收藏**：在父页面弹层内找 `.like-icon-and-text .btn-text`，文本为“收藏”时用事件序列点击；成功后文本变“已收藏”且图标带 active class——以该状态作为收藏成功的验证依据。
4. **关闭**：点击弹层内 `.close-btn`。
5. 页面可能残留多个弹层实例，不影响后续操作；可在下一轮打开前统一关闭。

## 六、节奏与风控（强制）

- **浏览节奏**：按人均约 10–15 秒浏览；滚动分段 + 随机停顿；每张卡片自然停留数秒。
- **收藏频率**：限次/分钟——每次收藏成功后按要求等待再操作下一人。
- **每日上限**：按平台/账号风控要求控制当日操作量；勿连续刷新/重复搜索。
- **验证码处理**：暂停等待人工输入（最长数分钟），**绝不绕过** CAPTCHA/拼图/滑块。
- 出现 rate limiting/安全挑战/异常 → **停止 → 观察 → 诊断 → 调整**；仅在失败明显属瞬时或可修正时重试，绝不盲目重试。

## 七、错误处理

- executeJS 返回 csp_blocked/detached：**不要重试**，改用 getContent 快照 + click/type。
- 元素 not_found：wait 1–3s 重读快照（refs 会变），或确认是否处于验证占位态。
- 筛选组合返回 0 结果：逐步放宽最具体条件（先去掉最苛刻项），逐人按全部条件评估。
- 页面回退到验证占位态（token/#saveCrop）：等待或关闭 `.crop_wrap` 后重试。
- 操作超时（timeout 10s）：属常态，继续 wait/重读状态，不要重复发同一请求。

## 八、数据与结果

- 严禁编造候选人信息；区分用户提供 / 页面观察 / 经验 / 推断。
- 收藏成功**必须以“已收藏”+图标 active 验证**，未验证不得声称成功。
- 输出 JSON：`{ candidates:[{name,intent,matchScore,reason,source}], status, summary, errorMessage?, mock }`；intent 用“已收藏”或“待人工收藏”；status 用 completed/partial。
- 任务结束前：验证最终状态、确认重要结果、**更新经验文档**（复用知识，避免重复踩坑）。

## 九、已验证经验速查（实证）

- **严禁 iframe 带参 URL**：一切筛选/操作必须留在 `https://www.zhipin.com/web/chat/search` 父页面内通过页面控件完成。
- 城市/关键词：页面内输入与下拉选择（不用 URL 参数）。
- 薪资双下拉用 executeJS 点击对应选项：可靠。
- 求职状态多选依次点击：可靠。
- 卡片详情弹层 + 收藏：executeJS 完整事件序列驱动，可靠（收藏按钮文本变更为成功标志）。
- 直接点击卡片链接：打开空白标签页且无内容：禁止。
- 单独打开搜索 iframe 作为主标签页：**禁止**——一切操作必须都在 /web/chat/search 父页面内完成，收藏更必须在父页面弹层内进行。