package com.buukle.agent.infrastructure.handler;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tenant row-level isolation for SELECT statements.
 *
 * <p>Two levels are deliberately separate:
 * <ul>
 *   <li>{@link #TENANT_TABLES}: tenant boundary only;</li>
 *   <li>{@link #TENANT_OWNER_TABLES}: tenant boundary plus
 *       {@code created_by = scope.username}.</li>
 * </ul>
 *
 * <p>A {@code null} scope means a system task and is not filtered. An empty
 * scope means "the user has no tenant assignment" and is rewritten to
 * {@code 1 = 0}, so the failure mode is "sees nothing", never "sees everything".
 */
@Slf4j
@Component
public class DataPermissionInterceptor implements InnerInterceptor {

    private static final String COLUMN_TENANT_ID = "tenant_id";
    private static final String COLUMN_CREATED_BY = "created_by";

    /**
     * Tenant-owned tables where rows are also personal. Keep this list aligned
     * with the historical {@code @WithTenant} behaviour.
     */
    private static final Set<String> TENANT_OWNER_TABLES = Set.of(
            "agent_session",
            "agent_run",
            "agent_instance",
            "agent_instance_capability",
            "agent_memory",
            "agent_model_provider",
            "agent_api_key",
            "agent_model_route",
            "capability_mcp",
            "capability_skill",
            "capability_cli",
            "agent_tool_call_record",
            "agent_compact_record",
            "agent_user_in_loop_record",
            "agent_llm_interaction_record",
            "agent_session_todo",
            "agent_session_input",
            "agent_completions",
            "agent_completions_prompt",
            "agent_completions_call",
            "agent_task",
            "agent_task_artifact",
            "agent_sub_agent_run",
            "agent_timeline",
            "agent_document",
            "agent_pending_wait",
            "agent_tool_approval",
            "agent_tool_approval_grant",
            "agent_message_feedback",
            "agent_chat_share",
            "agent_watchdog_run"
    );

    /**
     * Tenant-owned tables that are collaboration / audit data. They are filtered
     * by tenant only; their services enforce participant or ownership rules.
     */
    private static final Set<String> TENANT_ONLY_TABLES = Set.of(
            "agent_pending_clarification",
            "sys_audit_log",
            "agent_super_agent",
            "agent_channel_binding",
            "agent_thread",
            "agent_event",
            "agent_event_delivery",
            "agent_thread_subscription",
            "agent_work_item",
            "agent_outbound_message",
            "agent_router_fork",
            "im_conversation",
            "im_participant",
            "im_message",
            "im_read_cursor",
            "im_conversation_setting"
    );

    private static final Set<String> TENANT_TABLES = union(TENANT_OWNER_TABLES, TENANT_ONLY_TABLES);

    private static Set<String> union(Set<String> left, Set<String> right) {
        java.util.HashSet<String> all = new java.util.HashSet<>(left);
        all.addAll(right);
        return Set.copyOf(all);
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException {
        TenantScope scope = TenantScopeHolder.currentOrFallback();
        if (scope == null) {
            return;
        }
        String originalSql = boundSql.getSql();
        if (!mentionsTenantTable(originalSql)) {
            return;
        }
        String rewritten = applyTenantCondition(originalSql, scope);
        if (rewritten == null || rewritten.equals(originalSql)) {
            return;
        }
        PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
        mpBoundSql.sql(rewritten);
    }

    /**
     * Safety net for UPDATE/DELETE. Most services also load the row first (which
     * already goes through the SELECT filter), but a direct
     * {@code UPDATE ... WHERE id = ?} must not be able to cross tenants.
     */
    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        TenantScope scope = TenantScopeHolder.currentOrFallback();
        if (scope == null) {
            return;
        }
        BoundSql boundSql = sh.getBoundSql();
        String sql = boundSql.getSql();
        if (!mentionsTenantTable(sql)) {
            return;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Update update) {
                Table table = update.getTable();
                if (!isTenantTable(table)) {
                    return;
                }
                Expression condition = writeCondition(table, scope);
                if (condition == null) {
                    return;
                }
                Expression where = update.getWhere();
                update.setWhere(where == null ? condition : new AndExpression(where, condition));
                PluginUtils.mpBoundSql(boundSql).sql(update.toString());
            } else if (statement instanceof Delete delete) {
                Table table = delete.getTable();
                if (!isTenantTable(table)) {
                    return;
                }
                Expression condition = writeCondition(table, scope);
                if (condition == null) {
                    return;
                }
                Expression where = delete.getWhere();
                delete.setWhere(where == null ? condition : new AndExpression(where, condition));
                PluginUtils.mpBoundSql(boundSql).sql(delete.toString());
            }
        } catch (Exception e) {
            log.warn("[Tenant] write isolation rewrite failed, statement left unfiltered: {}", e.getMessage());
        }
    }

    private Expression writeCondition(Table table, TenantScope scope) {
        if (scope.isEmptyScope()) {
            return new EqualsTo(new LongValue(1), new LongValue(0));
        }
        Expression condition = tenantCondition(table, scope);
        if (scope.selfOnly() && TENANT_OWNER_TABLES.contains(normalizeName(table))) {
            String username = scope.username();
            if (username != null && !username.isBlank()) {
                condition = new AndExpression(condition, createdByCondition(table, username));
            }
        }
        return condition;
    }

    /** Test-only entry point for the SELECT rewrite. */
    String rewriteForTest(String sql, TenantScope scope) {
        return applyTenantCondition(sql, scope);
    }

    private boolean mentionsTenantTable(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        String lower = sql.toLowerCase();
        for (String table : TENANT_TABLES) {
            if (lower.contains(table)) {
                return true;
            }
        }
        return false;
    }

    private String applyTenantCondition(String sql, TenantScope scope) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) {
                return null;
            }
            if (!(select.getSelectBody() instanceof PlainSelect plainSelect)) {
                log.debug("[Tenant] non-plain select left unfiltered: {}", sql);
                return null;
            }
            if (plainSelect.getWhere() != null
                    && plainSelect.getWhere().toString().toLowerCase().contains(COLUMN_TENANT_ID)) {
                return null;
            }

            List<Table> tables = new ArrayList<>();
            collectTable(plainSelect.getFromItem(), tables);
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    collectTable(join.getRightItem(), tables);
                }
            }

            Expression condition = buildCondition(tables, scope, plainSelect);
            if (condition == null) {
                return null;
            }
            Expression where = plainSelect.getWhere();
            plainSelect.setWhere(where == null ? condition : new AndExpression(where, condition));
            return plainSelect.toString();
        } catch (Exception e) {
            // Do not break the whole page on a parser edge case. The strict mode
            // can be enabled later to turn this into a fail-closed 1 = 0.
            log.warn("[Tenant] SQL isolation rewrite failed, statement left unfiltered: {}", e.getMessage());
            return null;
        }
    }

    private void collectTable(FromItem fromItem, List<Table> tables) {
        if (fromItem instanceof Table table) {
            tables.add(table);
        }
    }

    private Expression buildCondition(List<Table> tables, TenantScope scope, PlainSelect plainSelect) {
        List<Table> scoped = new ArrayList<>();
        for (Table table : tables) {
            if (isTenantTable(table)) {
                scoped.add(table);
            }
        }
        if (scoped.isEmpty()) {
            return null;
        }
        if (scope.isEmptyScope()) {
            log.warn("[Tenant] empty visible tenant scope, query forced empty: {}", plainSelect);
            return new EqualsTo(new LongValue(1), new LongValue(0));
        }

        Expression result = null;
        for (Table table : scoped) {
            Expression condition = tenantCondition(table, scope);
            if (scope.selfOnly() && TENANT_OWNER_TABLES.contains(normalizeName(table))) {
                String username = scope.username();
                if (username != null && !username.isBlank()) {
                    condition = new AndExpression(condition, createdByCondition(table, username));
                }
            }
            result = result == null ? condition : new AndExpression(result, condition);
        }
        return result;
    }

    private Expression tenantCondition(Table table, TenantScope scope) {
        Column column = new Column(new Table(aliasOf(table)), COLUMN_TENANT_ID);
        List<Expression> values = new ArrayList<>(scope.visibleTenantIds().size());
        for (Long tenantId : scope.visibleTenantIds()) {
            values.add(new LongValue(tenantId));
        }
        return new InExpression()
                .withLeftExpression(column)
                .withRightExpression(new ParenthesedExpressionList<>(values));
    }

    private Expression createdByCondition(Table table, String username) {
        Column column = new Column(new Table(aliasOf(table)), COLUMN_CREATED_BY);
        return new EqualsTo(column, new StringValue(username));
    }

    private String aliasOf(Table table) {
        return table.getAlias() != null ? table.getAlias().getName() : table.getName();
    }

    private boolean isTenantTable(Table table) {
        return TENANT_TABLES.contains(normalizeName(table));
    }

    private String normalizeName(Table table) {
        String name = table.getName();
        return name == null ? "" : name.toLowerCase().replace("\"", "");
    }
}
