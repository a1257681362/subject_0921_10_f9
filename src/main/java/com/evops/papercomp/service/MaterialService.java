package com.evops.papercomp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.papercomp.common.PaperCompConstants;
import com.evops.papercomp.dto.MaterialRequest;
import com.evops.papercomp.dto.MaterialUpdateRequest;
import com.evops.papercomp.entity.Material;
import com.evops.papercomp.entity.MaterialChange;
import com.evops.papercomp.mapper.MaterialChangeMapper;
import com.evops.papercomp.mapper.MaterialMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 素材池：
 * - 题目新增记 ADD；修改关键属性逐字段记 UPDATE（前后值留痕）；停用 DISABLE / 重新启用 ENABLE；
 * - 不做物理删除：停用题历史卷面仍可追溯，组卷只从 ACTIVE 抽；
 * - 历史卷面冻结了入选题属性，素材事后任何增删改都不影响已生成卷面。
 */
@Service
public class MaterialService {

    private static final List<String> DIFFICULTIES = Arrays.asList("EASY", "MEDIUM", "HARD");
    private static final List<String> TYPES = Arrays.asList("SINGLE", "MULTI", "JUDGE", "FILL", "SHORT");

    private final MaterialMapper materialMapper;
    private final MaterialChangeMapper changeMapper;

    public MaterialService(MaterialMapper materialMapper, MaterialChangeMapper changeMapper) {
        this.materialMapper = materialMapper;
        this.changeMapper = changeMapper;
    }

    @Transactional
    public Material create(MaterialRequest request) {
        String project = normalizeProject(request.getProjectCode());
        validateKpAndDifficulty(request.getKnowledgePoint(), request.getDifficulty());
        String type = normalizeType(request.getQuestionType());
        if (request.getScore() == null || request.getScore().signum() < 0) {
            throw new BusinessException("MATERIAL_SCORE_INVALID", "题目分值不能为空且不能为负");
        }

        Material m = new Material();
        m.setCode(request.getCode().trim());
        m.setProjectCode(project);
        m.setKnowledgePoint(request.getKnowledgePoint().trim());
        m.setDifficulty(request.getDifficulty().trim().toUpperCase());
        m.setQuestionType(type);
        m.setScore(request.getScore());
        m.setStemDigest(request.getStemDigest());
        m.setStatus(PaperCompConstants.MATERIAL_ACTIVE);
        m.setRemark(request.getRemark());
        try {
            materialMapper.insert(m);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_MATERIAL_CODE", "题目编码已存在：" + m.getCode());
        }
        record(m.getId(), m.getCode(), PaperCompConstants.CHANGE_ADD, null, null,
                "新增题目[" + m.getKnowledgePoint() + "/" + m.getDifficulty() + "]", null);
        return m;
    }

    /** 关键属性修改：逐字段对比，变化字段各写一条 UPDATE 留痕。 */
    @Transactional
    public Material update(Long id, MaterialUpdateRequest request) {
        Material m = get(id);
        String reason = request.getReason();

        if (request.getKnowledgePoint() != null) {
            String kp = request.getKnowledgePoint().trim();
            if (kp.isEmpty()) {
                throw new BusinessException("MATERIAL_KP_INVALID", "知识点不能为空");
            }
            applyChange(m, "KNOWLEDGE_POINT", "知识点", m.getKnowledgePoint(), kp, reason);
            m.setKnowledgePoint(kp);
        }
        if (request.getDifficulty() != null) {
            String diff = request.getDifficulty().trim().toUpperCase();
            if (!DIFFICULTIES.contains(diff)) {
                throw new BusinessException("MATERIAL_DIFFICULTY_INVALID", "难度档只能是 EASY/MEDIUM/HARD：" + diff);
            }
            applyChange(m, "DIFFICULTY", "难度档", m.getDifficulty(), diff, reason);
            m.setDifficulty(diff);
        }
        if (request.getQuestionType() != null) {
            String type = normalizeType(request.getQuestionType());
            applyChange(m, "QUESTION_TYPE", "题型", m.getQuestionType(), type, reason);
            m.setQuestionType(type);
        }
        if (request.getScore() != null) {
            if (request.getScore().signum() < 0) {
                throw new BusinessException("MATERIAL_SCORE_INVALID", "题目分值不能为负");
            }
            applyChange(m, "SCORE", "分值",
                    m.getScore() == null ? null : m.getScore().toPlainString(),
                    request.getScore().toPlainString(), reason);
            m.setScore(request.getScore());
        }
        if (request.getStemDigest() != null) {
            applyChange(m, "STEM_DIGEST", "题干摘要", m.getStemDigest(), request.getStemDigest(), reason);
            m.setStemDigest(request.getStemDigest());
        }
        if (request.getRemark() != null) {
            m.setRemark(request.getRemark());
        }
        materialMapper.updateById(m);
        return m;
    }

    /** 停用（不物理删除）。 */
    @Transactional
    public Material disable(Long id, String reason) {
        Material m = get(id);
        if (PaperCompConstants.MATERIAL_DISABLED.equals(m.getStatus())) {
            throw new BusinessException("MATERIAL_ALREADY_DISABLED", "题目已停用，无需重复停用：" + m.getCode());
        }
        m.setStatus(PaperCompConstants.MATERIAL_DISABLED);
        materialMapper.updateById(m);
        record(m.getId(), m.getCode(), PaperCompConstants.CHANGE_DISABLE, "STATUS",
                PaperCompConstants.MATERIAL_ACTIVE, PaperCompConstants.MATERIAL_DISABLED, reason);
        return m;
    }

    /** 重新启用。 */
    @Transactional
    public Material enable(Long id, String reason) {
        Material m = get(id);
        if (PaperCompConstants.MATERIAL_ACTIVE.equals(m.getStatus())) {
            throw new BusinessException("MATERIAL_ALREADY_ACTIVE", "题目已是启用状态：" + m.getCode());
        }
        m.setStatus(PaperCompConstants.MATERIAL_ACTIVE);
        materialMapper.updateById(m);
        record(m.getId(), m.getCode(), PaperCompConstants.CHANGE_ENABLE, "STATUS",
                PaperCompConstants.MATERIAL_DISABLED, PaperCompConstants.MATERIAL_ACTIVE, reason);
        return m;
    }

    public Material get(Long id) {
        Material m = materialMapper.selectById(id);
        if (m == null) {
            throw new BusinessException("MATERIAL_NOT_FOUND", "题目不存在：id=" + id);
        }
        return m;
    }

    @Transactional(readOnly = true)
    public List<Material> list(String projectCode, String knowledgePoint, String difficulty,
                               String status, Boolean includeDisabled) {
        QueryWrapper<Material> wrapper = new QueryWrapper<Material>().orderByAsc("code");
        if (projectCode != null && !projectCode.trim().isEmpty()) {
            wrapper.eq("project_code", projectCode.trim().toUpperCase());
        }
        if (knowledgePoint != null && !knowledgePoint.trim().isEmpty()) {
            wrapper.eq("knowledge_point", knowledgePoint.trim());
        }
        if (difficulty != null && !difficulty.trim().isEmpty()) {
            wrapper.eq("difficulty", difficulty.trim().toUpperCase());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim().toUpperCase());
        } else if (!Boolean.TRUE.equals(includeDisabled)) {
            // 默认只列启用题（组卷候选口径）
            wrapper.eq("status", PaperCompConstants.MATERIAL_ACTIVE);
        }
        return materialMapper.selectList(wrapper);
    }

    @Transactional(readOnly = true)
    public List<MaterialChange> changes(Long materialId) {
        get(materialId);
        return changeMapper.selectList(new QueryWrapper<MaterialChange>()
                .eq("material_id", materialId)
                .orderByAsc("id"));
    }

    /** 组卷候选：项目内启用题。 */
    @Transactional(readOnly = true)
    public List<Material> activePool(String projectCode) {
        return materialMapper.selectList(new QueryWrapper<Material>()
                .eq("project_code", normalizeProject(projectCode))
                .eq("status", PaperCompConstants.MATERIAL_ACTIVE)
                .orderByAsc("code"));
    }

    // ---------- 内部 ----------

    private void applyChange(Material m, String field, String label, String oldVal, String newVal, String reason) {
        if (newVal == null ? oldVal == null : newVal.equals(oldVal)) {
            return;
        }
        record(m.getId(), m.getCode(), PaperCompConstants.CHANGE_UPDATE, field, oldVal, newVal, reason);
    }

    private void record(Long materialId, String code, String type, String field,
                        String oldVal, String newVal, String reason) {
        MaterialChange change = new MaterialChange();
        change.setMaterialId(materialId);
        change.setCode(code);
        change.setChangeType(type);
        change.setFieldName(field);
        change.setOldValue(oldVal);
        change.setNewValue(newVal);
        change.setReason(reason);
        change.setOperator(OperatorHolder.current());
        change.setChangedAt(LocalDateTime.now());
        changeMapper.insert(change);
    }

    private void validateKpAndDifficulty(String kp, String difficulty) {
        if (kp == null || kp.trim().isEmpty()) {
            throw new BusinessException("MATERIAL_KP_INVALID", "知识点不能为空");
        }
        if (difficulty == null || !DIFFICULTIES.contains(difficulty.trim().toUpperCase())) {
            throw new BusinessException("MATERIAL_DIFFICULTY_INVALID",
                    "难度档只能是 EASY/MEDIUM/HARD：" + difficulty);
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return "SINGLE";
        }
        String t = type.trim().toUpperCase();
        if (!TYPES.contains(t)) {
            throw new BusinessException("MATERIAL_TYPE_INVALID",
                    "题型只能是 SINGLE/MULTI/JUDGE/FILL/SHORT：" + t);
        }
        return t;
    }

    private String normalizeProject(String projectCode) {
        if (projectCode == null || projectCode.trim().isEmpty()) {
            return PaperCompConstants.PROJECT_DEFAULT;
        }
        return projectCode.trim().toUpperCase();
    }

    /** 供组卷服务把库题转成引擎快照（不暴露 ORM 对象给引擎）。 */
    public static List<String> difficulties() {
        return new ArrayList<>(DIFFICULTIES);
    }

    public static BigDecimal safeScore(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
