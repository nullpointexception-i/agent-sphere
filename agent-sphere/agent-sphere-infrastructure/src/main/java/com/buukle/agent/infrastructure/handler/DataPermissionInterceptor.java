package com.buukle.agent.infrastructure.handler;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.buukle.agent.common.context.TenantUtil;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Set;

@Slf4j
@Component
public class DataPermissionInterceptor implements InnerInterceptor {

    /**
     * Tables exempt from tenant filtering:
     * <ul>
     *   <li>{@code agent_user}: login/authentication happens before the tenant context is established;</li>
     *   <li>{@code agent_user_token}: sessions are located by token hash / user_id and read/written across
     *       entries; the row's created_by is the system identity at issuance, so tenant filtering would cause
     *       "sometimes the session cannot be found";</li>
     *   <li>{@code system_dic}: a global dictionary maintained by admins and read by all logged-in users;
     *       it must not be filtered by created_by, otherwise normal users cannot see options added by admins.</li>
     * </ul>
     */
    private static final Set<String> SKIP_TABLES = Set.of("agent_user", "agent_user_token", "system_dic");

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        String tenant = TenantUtil.get();
        if (tenant == null || tenant.isBlank()) return;

        try {
            String sql = boundSql.getSql();

            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect ps = (PlainSelect) select.getSelectBody();
            if (!(ps.getFromItem() instanceof Table table)) return;
            if (SKIP_TABLES.contains(table.getName().toLowerCase())) return;

            if (ps.getWhere() != null && ps.getWhere().toString().toLowerCase().contains("created_by")) return;

            String alias = table.getAlias() != null ? table.getAlias().getName() + "." : "";

            EqualsTo eq = new EqualsTo();
            eq.setLeftExpression(new Column(alias + "created_by"));
            eq.setRightExpression(new StringValue(tenant));

            ps.setWhere(ps.getWhere() == null ? eq : new AndExpression(ps.getWhere(), eq));
            String rewritten = select.toString();
//            log.info("DataPermission rewritten SQL: {}", rewritten);
            PluginUtils.mpBoundSql(boundSql).sql(rewritten);
        } catch (Exception ignored) {
        }
    }
}
