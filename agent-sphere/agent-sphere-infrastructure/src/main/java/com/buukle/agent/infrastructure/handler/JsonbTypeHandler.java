package com.buukle.agent.infrastructure.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;

@MappedTypes(String.class)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    private static String toJson(String value) {
        return (value == null || value.isBlank()) ? "{}" : value;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, toJson(parameter), Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object val = rs.getObject(columnName);
        return val != null ? val.toString() : null;
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object val = rs.getObject(columnIndex);
        return val != null ? val.toString() : null;
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Object val = cs.getObject(columnIndex);
        return val != null ? val.toString() : null;
    }
}
