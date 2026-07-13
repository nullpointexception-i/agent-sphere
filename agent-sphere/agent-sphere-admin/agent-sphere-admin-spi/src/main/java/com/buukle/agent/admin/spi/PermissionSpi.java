package com.buukle.agent.admin.spi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.dtvo.vo.SysPermissionVO;

import java.util.List;

public interface PermissionSpi {
    List<SysPermissionVO> listAll();

    Page<SysPermissionVO> listPage(int page, int size);

    List<SysPermissionVO> listByRoleId(Long roleId);

    List<SysPermissionVO> listByUserId(Long userId);

    List<String> listCodesByUserId(Long userId);

    List<String> listAllCodes();

    SysPermissionVO create(SysPermissionVO vo);

    SysPermissionVO update(Long id, SysPermissionVO vo);

    void delete(Long id);
}
