# Prompt Inventory (English Pass)

Model-facing and developer-facing prompt text translated in the 2026-11 refactor. The
backend unit suite (`mvn test`) passes; prompt behaviour still needs a staging golden-scenario
run with the configured model route before release.

| Area | Location | Notes |
|---|---|---|
| Skill template | `agent-sphere-common/.../skill/SkillTemplateValidator.java` | H1 + `## stepN` + `### stepN.M` structure preserved |
| Skill tool descriptions | `agent-sphere-capability/.../skillwrite` | `skill_library` / `skill_manage` action names unchanged |
| Builtin tool descriptions | `agent-sphere-capability/.../builtin-tool-*` | `TOOL_DESCRIPTION` and `@Schema` text; tool names unchanged |
| Chrome tool guidance | `agent-sphere-capability/.../tool-chrome` | Action names, `ref`, `frameId`, screenshots unchanged |
| UI skill template | `agent-sphere-ui/src/pages/capabilities/skill/index.tsx` | Mirrors the backend template |
| Task phase prompts | `agent-sphere-tasks/.../AgentTaskServiceImpl.java` | `execute` / `extract` / `refine` constraints preserved |
| Agent Router prompt | `agent-sphere-superagent/.../support/AgentRouterPrompt.java` | Tool names and `WORKER_REPORT` contract preserved |
| Group Router prompt | `agent-sphere-superagent/.../support/GroupRouterTemplate.java` | JSON output shape unchanged |
| Worker / Super-Agent prompt | `agent-sphere-superagent/.../support/SuperAgentPrompt.java` | Role and scope wording |
| Runner prompts | `agent-sphere-runtime/.../constants/RunnerConstants.java` | Task list, clarification, final-turn, silent-turn rules |
| Sub-agent prompts | `agent-sphere-runtime/.../runner/sub/{SessionSubRunner,SubAgentPolicy}.java` | Tool allowlist and status labels |
| Compaction prompt | `agent-sphere-runtime/.../service/CompactionService.java` | Summary limits and completeness rules |
| Completion output schema hint | `agent-sphere-completions/.../CompletionsServiceImpl.java` | JSON-only output rule preserved |
| IM preview text | `agent-sphere-im/.../ImMessageServiceImpl.java` | `[Message recalled]`, `[Image]`, etc. |

## Regression checklist

1. Run the full backend suite: `mvn test` (passes).
2. Run a Router Q&A, a Worker dispatch, a clarification resume, a `wait` resume and a
   compaction scenario against a staging model route.
3. Verify the model still emits the required JSON for Group Router and `finish`.
4. Verify `skill_manage` still rejects legacy templates and returns the English standard
   template.
5. If a prompt behaves worse, override it through `agent_system_config`
   (`super-agent.router-system-prompt`) or revert that single prompt; do not weaken the
   structural tests.
