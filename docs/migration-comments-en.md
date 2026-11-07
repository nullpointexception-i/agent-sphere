# Flyway Migration Index (English)

Historical migrations have Chinese comments and seed text. They are frozen: do not edit applied `V*.sql` files. This index gives every migration an English one-line summary for reviewers and operators.

**Rule:** new migrations must use English comments. Historical files are documented here instead of being rewritten (Flyway checksums would break).

| Version | File | English summary |
|---:|---|---|
| V1 | `V1__init_instance.sql` | Migration: init instance. |
| V2 | `V2__create_session_todo.sql` | Migration: create session todo. |
| V3 | `V3__add_indexes.sql` | Migration: add indexes. |
| V4 | `V4__add_instance_created_index.sql` | Migration: add instance created index. |
| V5 | `V5__add_tool_call_compression.sql` | Migration: add tool call compression. |
| V6 | `V6__tool_call_display_name_cn_en.sql` | Migration: tool call display name cn en. |
| V7 | `V7__create_document.sql` | Migration: create document. |
| V8 | `V8__security_upgrade.sql` | Migration: security upgrade. |
| V9 | `V9__create_system_config.sql` | Migration: create system config. |
| V10 | `V10__add_document_share.sql` | Migration: add document share. |
| V11 | `V11__add_rbac_tables.sql` | Migration: add rbac tables. |
| V12 | `V12__seed_rbac_data.sql` | Migration: seed rbac data. |
| V13 | `V13__grant_user_role_admin_read.sql` | Migration: grant user role admin read. |
| V14 | `V14__remove_admin_role.sql` | Migration: remove admin role. |
| V15 | `V15__refine_admin_permissions.sql` | Migration: refine admin permissions. |
| V16 | `V16__rename_user_menu.sql` | Migration: rename user menu. |
| V17 | `V17__add_role_crud_permissions.sql` | Migration: add role crud permissions. |
| V18 | `V18__add_instance_bind_model_permission.sql` | Migration: add instance bind model permission. |
| V19 | `V19__add_user_login_tracking.sql` | Migration: add user login tracking. |
| V20 | `V20__create_audit_log.sql` | Migration: create audit log. |
| V21 | `V21__add_audit_log_permission.sql` | Migration: add audit log permission. |
| V22 | `V22__create_pending_clarification.sql` | Migration: create pending clarification. |
| V23 | `V23__add_clarification_message_id.sql` | Migration: add clarification message id. |
| V24 | `V24__add_clarification_audit_columns.sql` | Migration: add clarification audit columns. |
| V25 | `V25__add_clarification_id.sql` | Migration: add clarification id. |
| V26 | `V26__drop_clarification_expires_at.sql` | Migration: drop clarification expires at. |
| V27 | `V27__add_sso_identity.sql` | Migration: add sso identity. |
| V28 | `V28__fix_identity_provider_delete_flag.sql` | Migration: fix identity provider delete flag. |
| V29 | `V29__add_identity_provider_permissions.sql` | Migration: add identity provider permissions. |
| V30 | `V30__add_sso_base_url_config.sql` | Migration: add sso base url config. |
| V31 | `V31__create_completions_tasks.sql` | Migration: create completions tasks. |
| V32 | `V32__add_completions_permissions.sql` | Migration: add completions permissions. |
| V33 | `V33__add_completions_tasks_common_columns.sql` | Migration: add completions tasks common columns. |
| V34 | `V34__seed_bole_completions.sql` | Migration: seed bole completions. |
| V35 | `V35__add_task_callback_url.sql` | Migration: add task callback url. |
| V36 | `V36__add_tasks_permissions.sql` | Migration: add tasks permissions. |
| V37 | `V37__add_sso_identity_display_subject.sql` | Migration: add sso identity display subject. |
| V38 | `V38__add_business_type.sql` | Migration: add business type. |
| V39 | `V39__add_identity_provider_resource_template.sql` | Migration: add identity provider resource template. |
| V40 | `V40__create_task_artifact.sql` | Migration: create task artifact. |
| V41 | `V41__seed_task_artifact.sql` | Migration: seed task artifact. |
| V42 | `V42__add_run_reasoning.sql` | Migration: add run reasoning. |
| V43 | `V43__add_task_polling_columns.sql` | Migration: add task polling columns. |
| V44 | `V44__add_run_loop_capped.sql` | Migration: add run loop capped. |
| V45 | `V45__fix_run_loop_capped_type.sql` | Migration: fix run loop capped type. |
| V46 | `V46__add_plugin_download_and_user_init_template.sql` | Migration: add plugin download and user init template. |
| V47 | `V47__normalize_plugin_download_url.sql` | Migration: normalize plugin download url. |
| V48 | `V48__create_agent_file_store.sql` | Migration: create agent file store. |
| V49 | `V49__add_plugin_store_url.sql` | Migration: add plugin store url. |
| V50 | `V50__seed_bole_completions_project_experience.sql` | Migration: seed bole completions project experience. |
| V51 | `V51__seed_bole_completions_profile_generate.sql` | Migration: seed bole completions profile generate. |
| V52 | `V52__update_profile_generate_input_schema.sql` | Migration: update profile generate input schema. |
| V53 | `V53__seed_bole_completions_resume_parse_current_city.sql` | Migration: seed bole completions resume parse current city. |
| V54 | `V54__seed_bole_completions_resume_parse_nationality.sql` | Migration: seed bole completions resume parse nationality. |
| V55 | `V55__add_reasoning_and_sub_agent_run.sql` | Migration: add reasoning and sub agent run. |
| V56 | `V56__create_agent_timeline.sql` | Migration: create agent timeline. |
| V57 | `V57__add_instance_max_loop_count.sql` | Migration: add instance max loop count. |
| V58 | `V58__add_route_supports_attachment.sql` | Migration: add route supports attachment. |
| V59 | `V59__add_run_attachments.sql` | Migration: add run attachments. |
| V60 | `V60__switch_default_route_to_vision_model.sql` | Migration: switch default route to vision model. |
| V61 | `V61__optimize_five_dim_match_prompt.sql` | Migration: optimize five dim match prompt. |
| V62 | `V62__seed_default_resource_template.sql` | Migration: seed default resource template. |
| V63 | `V63__repair_default_resource_template.sql` | Migration: repair default resource template. |
| V64 | `V64__add_instance_config.sql` | Migration: add instance config. |
| V65 | `V65__add_llm_interaction_usage.sql` | Migration: add llm interaction usage. |
| V66 | `V66__seed_llm_defaults_config.sql` | Migration: seed llm defaults config. |
| V67 | `V67__set_llm_defaults_config_value.sql` | Migration: set llm defaults config value. |
| V68 | `V68__add_skill_hub_columns.sql` | Migration: add skill hub columns. |
| V69 | `V69__seed_skill_hub_permissions.sql` | Migration: seed skill hub permissions. |
| V70 | `V70__add_skill_version_auto_update.sql` | Migration: add skill version auto update. |
| V71 | `V71__add_mcp_test_permission.sql` | Migration: add mcp test permission. |
| V72 | `V72__add_task_timeout_seconds.sql` | Migration: add task timeout seconds. |
| V73 | `V73__simplify_profile_generate_schema.sql` | Migration: simplify profile generate schema. |
| V74 | `V74__session_cleanup_indexes.sql` | Migration: session cleanup indexes. |
| V75 | `V75__seed_session_cleanup_configs.sql` | Migration: seed session cleanup configs. |
| V76 | `V76__create_session_cleanup_run.sql` | Migration: create session cleanup run. |
| V77 | `V77__seed_session_cleanup_log_retention.sql` | Migration: seed session cleanup log retention. |
| V78 | `V78__add_cleanup_run_total_sessions.sql` | Migration: add cleanup run total sessions. |
| V79 | `V79__add_skill_sync_metadata.sql` | Migration: add skill sync metadata. |
| V80 | `V80__create_skill_sync_run.sql` | Migration: create skill sync run. |
| V81 | `V81__seed_skill_sync_configs.sql` | Migration: seed skill sync configs. |
| V82 | `V82__add_run_loop_count.sql` | Migration: add run loop count. |
| V83 | `V83__create_pending_wait.sql` | Migration: create pending wait. |
| V84 | `V84__add_session_todo_skill_ref.sql` | Migration: add session todo skill ref. |
| V85 | `V85__fix_default_skill_template.sql` | Migration: fix default skill template. |
| V86 | `V86__pending_wait_retry.sql` | Migration: pending wait retry. |
| V87 | `V87__add_run_error_message.sql` | Migration: add run error message. |
| V88 | `V88__add_run_loop_limit.sql` | Migration: add run loop limit. |
| V89 | `V89__add_instance_session_switches.sql` | Migration: add instance session switches. |
| V90 | `V90__sso_base_url_https.sql` | Migration: sso base url https. |
| V91 | `V91__seed_plugin_min_extension_version.sql` | Migration: seed plugin min extension version. |
| V92 | `V92__timeline_ref_compact.sql` | Migration: timeline ref compact. |
| V93 | `V93__run_compaction.sql` | Migration: run compaction. |
| V94 | `V94__capability_tool_policy.sql` | Migration: capability tool policy. |
| V95 | `V95__session_run_mode.sql` | Migration: session run mode. |
| V96 | `V96__tool_approval.sql` | Migration: tool approval. |
| V97 | `V97__dedicated_tool_approval.sql` | Migration: dedicated tool approval. |
| V98 | `V98__split_document_read_write_template.sql` | Migration: split document read write template. |
| V99 | `V99__clarification_multiple.sql` | Migration: clarification multiple. |
| V100 | `V100__timeline_tool_approval.sql` | Migration: timeline tool approval. |
| V101 | `V101__tool_approval_grant_display.sql` | Migration: tool approval grant display. |
| V102 | `V102__message_feedback.sql` | Migration: message feedback. |
| V103 | `V103__chat_share.sql` | Migration: chat share. |
| V104 | `V104__run_scan_indexes.sql` | Migration: run scan indexes. |
| V105 | `V105__clarification_description.sql` | Migration: clarification description. |
| V106 | `V106__add_completion_protocol.sql` | Migration: add completion protocol. |
| V107 | `V107__model_route_completion_protocol.sql` | Migration: model route completion protocol. |
| V108 | `V108__document_version.sql` | Migration: document version. |
| V109 | `V109__timeline_dedup_and_indexes.sql` | Migration: timeline dedup and indexes. |
| V110 | `V110__user_token_sessions.sql` | Migration: user token sessions. |
| V111 | `V111__timeline_state_and_turn.sql` | Migration: timeline state and turn. |
| V112 | `V112__interaction_attempt_no.sql` | Migration: interaction attempt no. |
| V113 | `V113__tool_call_turn_no.sql` | Migration: tool call turn no. |
| V114 | `V114__tool_call_execution_device.sql` | Migration: tool call execution device. |
| V115 | `V115__llm_retry_suspend.sql` | Migration: llm retry suspend. |
| V116 | `V116__run_model_route.sql` | Migration: run model route. |
| V117 | `V117__llm_retry_record.sql` | Migration: llm retry record. |
| V118 | `V118__reasoning_effort.sql` | Migration: reasoning effort. |
| V119 | `V119__route_token_limits_required.sql` | Migration: route token limits required. |
| V120 | `V120__super_agent_core.sql` | Migration: super agent core. |
| V121 | `V121__im_core.sql` | Migration: im core. |
| V122 | `V122__session_instance_ext.sql` | Migration: session instance ext. |
| V123 | `V123__super_agent_seed.sql` | Migration: super agent seed. |
| V124 | `V124__work_item_trigger_event.sql` | Migration: work item trigger event. |
| V125 | `V125__work_item_attempt.sql` | Migration: work item attempt. |
| V126 | `V126__superagent_runtime_hardening.sql` | Migration: superagent runtime hardening. |
| V127 | `V127__superagent_audit_identity.sql` | Migration: superagent audit identity. |
| V128 | `V128__work_item_status_done.sql` | Migration: work item status done. |
| V129 | `V129__agent_router_context.sql` | Migration: agent router context. |
| V130 | `V130__agent_watchdog.sql` | Migration: agent watchdog. |
| V131 | `V131__superagent_im_timestamp_without_tz.sql` | Migration: superagent im timestamp without tz. |
| V132 | `V132__superagent_scheduler_indexes.sql` | Migration: superagent scheduler indexes. |
| V133 | `V133__outbound_lease.sql` | Migration: outbound lease. |
| V134 | `V134__super_agent_router_prompt.sql` | Migration: super agent router prompt. |
| V135 | `V135__model_route_default.sql` | Migration: model route default. |
| V136 | `V136__resource_template_default_route.sql` | Migration: resource template default route. |
| V137 | `V137__im_conversation_setting_and_pin.sql` | Migration: im conversation setting and pin. |
| V138 | `V138__im_conversation_setting_timestamp.sql` | Migration: im conversation setting timestamp. |
| V139 | `V139__im_conversation_hidden.sql` | Migration: im conversation hidden. |
| V140 | `V140__system_dic.sql` | Migration: system dic. |
| V141 | `V141__super_agent_position.sql` | Migration: super agent position. |
| V142 | `V142__system_dic_timestamp.sql` | Migration: system dic timestamp. |
| V143 | `V143__agent_thread_router_session.sql` | Migration: agent thread router session. |
| V144 | `V144__retire_guardian_session.sql` | Migration: retire guardian session. |
| V145 | `V145__super_agent_router_reply_protocol.sql` | Migration: super agent router reply protocol. |
| V146 | `V146__router_plain_reply_forbids_send_message.sql` | Migration: router plain reply forbids send message. |
| V147 | `V147__llm_stream_retry_observability.sql` | Migration: llm stream retry observability. |
| V148 | `V148__agent_session_input.sql` | Migration: agent session input. |
| V149 | `V149__run_no_clarification.sql` | Migration: run no clarification. |
