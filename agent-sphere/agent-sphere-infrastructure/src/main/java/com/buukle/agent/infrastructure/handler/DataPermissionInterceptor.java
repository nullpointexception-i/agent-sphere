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
     * 豁免租户过滤的表：
     * <ul>
     *   <li>{@code agent_user}：登录/鉴权发生在租户上下文建立之前；</li>
     *   <li>{@code agent_user_token}：会话按 token hash / user_id 定位，跨入口读写，
     *       行上的 created_by 是签发时的系统身份，用租户过滤会导致「有时查不到会话」。</li>
     * </ul>
     */
    private static final Set<String> SKIP_TABLES = Set.of("agent_user", "agent_user_token");

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
