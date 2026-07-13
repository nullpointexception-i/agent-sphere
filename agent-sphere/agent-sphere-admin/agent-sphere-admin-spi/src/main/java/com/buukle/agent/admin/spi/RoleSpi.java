package com.buukle.agent.admin.spi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.dtvo.vo.SysRoleVO;

import java.util.List;

public interface RoleSpi {
    List<SysRoleVO> listAll();

    Page<SysRoleVO> listPage(int page, int size);

    List<SysRoleVO> listByUserId(Long userId);

    SysRoleVO create(SysRoleVO vo);

    SysRoleVO update(Long id, SysRoleVO vo);

    void delete(Long id);

    void assignPermissions(Long roleId, List<Long> permissionIds);

    void assignRoles(Long userId, List<Long> roleIds);
}
