package com.buukle.agent.infrastructure.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.dict.SystemDicConstants;
import com.buukle.agent.common.dict.SystemDicEntry;
import com.buukle.agent.common.dict.SystemDicSpi;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.infrastructure.controller.dtvo.SystemDicSaveDTO;
import com.buukle.agent.infrastructure.controller.dtvo.SystemDicVO;
import com.buukle.agent.infrastructure.persistence.SystemDic;
import com.buukle.agent.infrastructure.persistence.SystemDicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SystemDicServiceImpl implements SystemDicSpi, SystemDicService {

    private final SystemDicMapper systemDicMapper;

    @Override
    public List<SystemDicEntry> listActive(String dicType) {
        return activeQuery(dicType)
                .stream()
                .map(item -> new SystemDicEntry(item.getCode(), item.getNameCn(), item.getNameEn()))
                .toList();
    }

    @Override
    public SystemDicEntry findActive(String dicType, String code) {
        if (dicType == null || dicType.isBlank() || code == null || code.isBlank()) {
            return null;
        }
        SystemDic item = systemDicMapper.selectOne(Wrappers.<SystemDic>lambdaQuery()
                .eq(SystemDic::getDicType, dicType.trim())
                .eq(SystemDic::getCode, code.trim())
                .eq(SystemDic::getStatus, SystemDicConstants.STATUS_ACTIVE)
                .last("LIMIT 1"));
        return item == null ? null
                : new SystemDicEntry(item.getCode(), item.getNameCn(), item.getNameEn());
    }

    @Override
    public List<SystemDicVO> list(String dicType, String keyword) {
        String type = normalize(dicType);
        String kw = keyword == null ? null : keyword.trim();
        var query = Wrappers.<SystemDic>lambdaQuery()
                .eq(type != null, SystemDic::getDicType, type)
                .orderByAsc(SystemDic::getSortNo)
                .orderByAsc(SystemDic::getId);
        if (kw != null && !kw.isBlank()) {
            query.and(w -> w.like(SystemDic::getCode, kw)
                    .or().like(SystemDic::getNameCn, kw)
                    .or().like(SystemDic::getNameEn, kw));
        }
        return systemDicMapper.selectList(query).stream().map(this::toVO).toList();
    }

    @Override
    public SystemDicVO create(SystemDicSaveDTO dto) {
        String type = requireType(dto.getDicType());
        String code = requireCode(dto.getCode());
        ensureCodeAvailable(type, code, null);
        SystemDic item = new SystemDic();
        item.setDicType(type);
        item.setCode(code);
        item.setNameCn(dto.getNameCn().trim());
        item.setNameEn(dto.getNameEn().trim());
        item.setSortNo(dto.getSortNo() == null ? 0 : dto.getSortNo());
        item.setStatus(normalizeStatus(dto.getStatus()));
        item.setRemark(dto.getRemark());
        systemDicMapper.insert(item);
        return toVO(item);
    }

    @Override
    public SystemDicVO update(Long id, SystemDicSaveDTO dto) {
        SystemDic item = requireItem(id);
        String type = requireType(dto.getDicType());
        String code = requireCode(dto.getCode());
        ensureCodeAvailable(type, code, id);
        item.setDicType(type);
        item.setCode(code);
        item.setNameCn(dto.getNameCn().trim());
        item.setNameEn(dto.getNameEn().trim());
        item.setSortNo(dto.getSortNo() == null ? 0 : dto.getSortNo());
        item.setStatus(normalizeStatus(dto.getStatus()));
        item.setRemark(dto.getRemark());
        systemDicMapper.updateById(item);
        return toVO(item);
    }

    @Override
    public void delete(Long id) {
        requireItem(id);
        systemDicMapper.deleteById(id);
    }

    private List<SystemDic> activeQuery(String dicType) {
        return systemDicMapper.selectList(Wrappers.<SystemDic>lambdaQuery()
                .eq(SystemDic::getDicType, requireType(dicType))
                .eq(SystemDic::getStatus, SystemDicConstants.STATUS_ACTIVE)
                .orderByAsc(SystemDic::getSortNo)
                .orderByAsc(SystemDic::getId));
    }

    private void ensureCodeAvailable(String dicType, String code, Long excludeId) {
        var query = Wrappers.<SystemDic>lambdaQuery()
                .eq(SystemDic::getDicType, dicType)
                .eq(SystemDic::getCode, code);
        if (excludeId != null) {
            query.ne(SystemDic::getId, excludeId);
        }
        if (systemDicMapper.selectCount(query) > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "dictionary code already exists: " + code);
        }
    }

    private SystemDic requireItem(Long id) {
        SystemDic item = systemDicMapper.selectById(id);
        if (item == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "dictionary item not found: " + id);
        }
        return item;
    }

    private static String requireType(String dicType) {
        String type = normalize(dicType);
        if (type == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "dictionary type is required");
        }
        return type;
    }

    private static String requireCode(String code) {
        String value = normalize(code);
        if (value == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "dictionary code is required");
        }
        return value;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeStatus(String status) {
        String value = normalize(status);
        return value == null ? SystemDicConstants.STATUS_ACTIVE : value.toUpperCase();
    }

    private SystemDicVO toVO(SystemDic item) {
        SystemDicVO vo = new SystemDicVO();
        vo.setId(item.getId());
        vo.setDicType(item.getDicType());
        vo.setCode(item.getCode());
        vo.setNameCn(item.getNameCn());
        vo.setNameEn(item.getNameEn());
        vo.setSortNo(item.getSortNo());
        vo.setStatus(item.getStatus());
        vo.setRemark(item.getRemark());
        return vo;
    }
}
