package com.buukle.agent.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.admin.domain.SysPermission;
import com.buukle.agent.admin.domain.SysRolePermission;
import com.buukle.agent.admin.domain.SysUserRole;
import com.buukle.agent.admin.dtvo.vo.SysPermissionVO;
import com.buukle.agent.admin.exception.AdminErrorCode;
import com.buukle.agent.admin.repository.SysPermissionMapper;
import com.buukle.agent.admin.repository.SysRolePermissionMapper;
import com.buukle.agent.admin.repository.SysUserRoleMapper;
import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionSpi {

    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;

    @Override
    public List<SysPermissionVO> listAll() {
        List<SysPermission> all = permissionMapper.selectList(
                Wrappers.lambdaQuery(SysPermission.class).orderByAsc(SysPermission::getSort));
        return buildTree(all, null);
    }

    @Override
    public Page<SysPermissionVO> listPage(int page, int size) {
        var mpPage = permissionMapper.selectPage(new Page<>(page, size),
                Wrappers.lambdaQuery(SysPermission.class).orderByAsc(SysPermission::getSort));
        var voPage = new Page<SysPermissionVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVo).toList());
        return voPage;
    }

    @Override
    public List<SysPermissionVO> listByRoleId(Long roleId) {
        Set<String> myCodes = AuthContext.getPermissions();
        List<SysRolePermission> rps = rolePermissionMapper.selectList(
                Wrappers.lambdaQuery(SysRolePermission.class).eq(SysRolePermission::getRoleId, roleId));
        Set<Long> assignedIds = rps.stream().map(SysRolePermission::getPermissionId).collect(Collectors.toSet());
        List<SysPermission> all = permissionMapper.selectList(
                Wrappers.lambdaQuery(SysPermission.class).orderByAsc(SysPermission::getSort));
        List<SysPermission> filtered = (myCodes == null || myCodes.isEmpty())
                ? all
                : all.stream().filter(p -> myCodes.contains(p.getCode())).toList();
        return buildTree(filtered, assignedIds);
    }

    @Override
    public List<SysPermissionVO> listByUserId(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return List.of();
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        List<SysRolePermission> rps = rolePermissionMapper.selectList(
                Wrappers.lambdaQuery(SysRolePermission.class).in(SysRolePermission::getRoleId, roleIds));
        Set<Long> permissionIds = rps.stream().map(SysRolePermission::getPermissionId).collect(Collectors.toSet());
        if (permissionIds.isEmpty()) return List.of();
        List<SysPermission> permissions = permissionMapper.selectBatchIds(permissionIds);
        return permissions.stream().map(this::toVo).toList();
    }

    @Override
    public List<String> listCodesByUserId(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return List.of();
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        List<SysRolePermission> rps = rolePermissionMapper.selectList(
                Wrappers.lambdaQuery(SysRolePermission.class).in(SysRolePermission::getRoleId, roleIds));
        if (rps.isEmpty()) return List.of();
        List<Long> permissionIds = rps.stream().map(SysRolePermission::getPermissionId).toList();
        List<SysPermission> permissions = permissionMapper.selectBatchIds(permissionIds);
        return permissions.stream().map(SysPermission::getCode).toList();
    }

    @Override
    public List<String> listAllCodes() {
        return permissionMapper.selectList(null).stream()
                .map(SysPermission::getCode).toList();
    }

    @Override
    public SysPermissionVO create(SysPermissionVO vo) {
        long count = permissionMapper.selectCount(
                Wrappers.lambdaQuery(SysPermission.class).eq(SysPermission::getCode, vo.getCode()));
        if (count > 0) throw new BizException(AdminErrorCode.PERMISSION_CODE_EXISTS);
        SysPermission entity = toEntity(vo);
        permissionMapper.insert(entity);
        vo.setId(entity.getId());
        return vo;
    }

    @Override
    public SysPermissionVO update(Long id, SysPermissionVO vo) {
        SysPermission entity = permissionMapper.selectById(id);
        if (entity == null) throw new BizException(AdminErrorCode.PERMISSION_CODE_EXISTS);
        if (vo.getName() != null) entity.setName(vo.getName());
        if (vo.getDescription() != null) entity.setDescription(vo.getDescription());
        if (vo.getSort() != null) entity.setSort(vo.getSort());
        permissionMapper.updateById(entity);
        return toVo(entity);
    }

    @Override
    public void delete(Long id) {
        rolePermissionMapper.delete(Wrappers.lambdaQuery(SysRolePermission.class)
                .eq(SysRolePermission::getPermissionId, id));
        permissionMapper.deleteById(id);
    }

    private List<SysPermissionVO> buildTree(List<SysPermission> all, Set<Long> assignedIds) {
        Map<Long, List<SysPermission>> grouped = all.stream()
                .collect(Collectors.groupingBy(p -> p.getParentId() != null ? p.getParentId() : 0L));
        List<SysPermissionVO> tree = new ArrayList<>();
        for (SysPermission p : grouped.getOrDefault(0L, List.of())) {
            SysPermissionVO vo = toVo(p);
            vo.setChildren(buildChildren(p.getId(), grouped, assignedIds));
            tree.add(vo);
        }
        tree.sort(Comparator.comparingInt(SysPermissionVO::getSort));
        return tree;
    }

    private List<SysPermissionVO> buildChildren(Long parentId, Map<Long, List<SysPermission>> grouped,
                                                Set<Long> assignedIds) {
        List<SysPermission> children = grouped.getOrDefault(parentId, List.of());
        return children.stream().map(p -> {
            SysPermissionVO vo = toVo(p);
            vo.setChildren(buildChildren(p.getId(), grouped, assignedIds));
            vo.setAssigned(assignedIds != null && assignedIds.contains(p.getId()));
            return vo;
        }).toList();
    }

    private SysPermissionVO toVo(SysPermission p) {
        SysPermissionVO vo = new SysPermissionVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setCode(p.getCode());
        vo.setType(p.getType());
        vo.setParentId(p.getParentId());
        vo.setSort(p.getSort());
        vo.setDescription(p.getDescription());
        vo.setCreatedAt(p.getCreatedAt());
        return vo;
    }

    private SysPermission toEntity(SysPermissionVO vo) {
        SysPermission entity = new SysPermission();
        entity.setName(vo.getName());
        entity.setCode(vo.getCode());
        entity.setType(vo.getType());
        entity.setParentId(vo.getParentId());
        entity.setSort(vo.getSort());
        entity.setDescription(vo.getDescription());
        return entity;
    }
}
