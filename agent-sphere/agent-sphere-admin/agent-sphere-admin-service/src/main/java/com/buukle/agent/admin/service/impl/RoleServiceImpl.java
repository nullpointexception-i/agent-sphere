package com.buukle.agent.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.domain.SysPermission;
import com.buukle.agent.admin.domain.SysRole;
import com.buukle.agent.admin.domain.SysRolePermission;
import com.buukle.agent.admin.domain.SysUserRole;
import com.buukle.agent.admin.dtvo.vo.SysRoleVO;
import com.buukle.agent.admin.exception.AdminErrorCode;
import com.buukle.agent.admin.repository.SysPermissionMapper;
import com.buukle.agent.admin.repository.SysRoleMapper;
import com.buukle.agent.admin.repository.SysRolePermissionMapper;
import com.buukle.agent.admin.repository.SysUserRoleMapper;
import com.buukle.agent.admin.spi.RoleSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleSpi {

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysPermissionMapper permissionMapper;

    @Override
    public List<SysRoleVO> listAll() {
        return roleMapper.selectList(Wrappers.lambdaQuery(SysRole.class).orderByAsc(SysRole::getId))
                .stream().map(this::toVo).toList();
    }

    @Override
    public Page<SysRoleVO> listPage(int page, int size) {
        var mpPage = roleMapper.selectPage(new Page<>(page, size),
                Wrappers.lambdaQuery(SysRole.class).orderByAsc(SysRole::getId));
        var voPage = new Page<SysRoleVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVo).toList());
        return voPage;
    }

    @Override
    public List<SysRoleVO> listByUserId(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return List.of();
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        return roleMapper.selectBatchIds(roleIds).stream().map(this::toVo).toList();
    }

    @Override
    public SysRoleVO create(SysRoleVO vo) {
        long count = roleMapper.selectCount(
                Wrappers.lambdaQuery(SysRole.class).eq(SysRole::getCode, vo.getCode()));
        if (count > 0) throw new BizException(AdminErrorCode.ROLE_CODE_EXISTS);
        SysRole entity = toEntity(vo);
        roleMapper.insert(entity);
        vo.setId(entity.getId());
        return vo;
    }

    @Override
    public SysRoleVO update(Long id, SysRoleVO vo) {
        SysRole entity = roleMapper.selectById(id);
        if (entity == null) throw new BizException(AdminErrorCode.ROLE_CODE_EXISTS);
        if (vo.getName() != null) entity.setName(vo.getName());
        if (vo.getDescription() != null) entity.setDescription(vo.getDescription());
        roleMapper.updateById(entity);
        return toVo(entity);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        rolePermissionMapper.delete(Wrappers.lambdaQuery(SysRolePermission.class)
                .eq(SysRolePermission::getRoleId, id));
        userRoleMapper.delete(Wrappers.lambdaQuery(SysUserRole.class)
                .eq(SysUserRole::getUserId, id));
        roleMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        if (!AuthContext.isSuperAdmin()) {
            Set<Long> ownedIds = new HashSet<>();
            Set<String> myPerms = AuthContext.getPermissions();
            if (myPerms != null && !myPerms.isEmpty() && permissionIds != null && !permissionIds.isEmpty()) {
                List<SysPermission> allPerms = permissionMapper.selectBatchIds(permissionIds);
                for (SysPermission p : allPerms) {
                    if (myPerms.contains(p.getCode())) ownedIds.add(p.getId());
                }
            }
            permissionIds = ownedIds.stream().toList();
        }
        rolePermissionMapper.delete(Wrappers.lambdaQuery(SysRolePermission.class)
                .eq(SysRolePermission::getRoleId, roleId));
        if (permissionIds != null && !permissionIds.isEmpty()) {
            List<SysRolePermission> list = permissionIds.stream().map(pid -> {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(pid);
                return rp;
            }).toList();
            list.forEach(rolePermissionMapper::insert);
        }
    }

    @Override
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(Wrappers.lambdaQuery(SysUserRole.class)
                .eq(SysUserRole::getUserId, userId));
        if (roleIds != null && !roleIds.isEmpty()) {
            List<SysUserRole> list = roleIds.stream().map(rid -> {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(rid);
                return ur;
            }).toList();
            list.forEach(userRoleMapper::insert);
        }
    }

    private SysRoleVO toVo(SysRole role) {
        SysRoleVO vo = new SysRoleVO();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setCode(role.getCode());
        vo.setDescription(role.getDescription());
        vo.setCreatedAt(role.getCreatedAt());
        return vo;
    }

    private SysRole toEntity(SysRoleVO vo) {
        SysRole entity = new SysRole();
        entity.setName(vo.getName());
        entity.setCode(vo.getCode());
        entity.setDescription(vo.getDescription());
        return entity;
    }
}
