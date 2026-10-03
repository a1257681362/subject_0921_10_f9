package com.evops.papercomp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.papercomp.common.PaperCompConstants;
import com.evops.papercomp.dto.PickAlgorithmRequest;
import com.evops.papercomp.dto.PickAlgorithmVersionRequest;
import com.evops.papercomp.dto.PickParams;
import com.evops.papercomp.entity.PickAlgorithm;
import com.evops.papercomp.entity.PickAlgorithmVersion;
import com.evops.papercomp.mapper.PickAlgorithmMapper;
import com.evops.papercomp.mapper.PickAlgorithmVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 抽选算法模块（模块 / 版本 / 状态）：
 * - 算法按项目建档，算法下挂多个版本；
 * - 版本 DRAFT → ACTIVE → DISABLED：发布即冻结参数 JSON，自动停用同算法旧生效版；
 * - 组卷只认项目当前 ACTIVE 版本；停用不删版本，历史卷面永久保留版本号。
 */
@Service
public class PickAlgorithmService {

    private final PickAlgorithmMapper algorithmMapper;
    private final PickAlgorithmVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public PickAlgorithmService(PickAlgorithmMapper algorithmMapper,
                                PickAlgorithmVersionMapper versionMapper,
                                ObjectMapper objectMapper) {
        this.algorithmMapper = algorithmMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 算法模块 ----------

    @Transactional
    public PickAlgorithm createAlgorithm(PickAlgorithmRequest request) {
        PickAlgorithm algorithm = new PickAlgorithm();
        algorithm.setCode(request.getCode().trim());
        algorithm.setName(request.getName().trim());
        algorithm.setProjectCode(normalizeProject(request.getProjectCode()));
        algorithm.setRemark(request.getRemark());
        try {
            algorithmMapper.insert(algorithm);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_PICK_ALGORITHM_CODE", "抽选算法编码已存在：" + algorithm.getCode());
        }
        return algorithm;
    }

    public List<PickAlgorithm> listAlgorithms(String projectCode) {
        QueryWrapper<PickAlgorithm> wrapper = new QueryWrapper<PickAlgorithm>()
                .orderByAsc("project_code", "code");
        if (projectCode != null && !projectCode.trim().isEmpty()) {
            wrapper.eq("project_code", projectCode.trim());
        }
        return algorithmMapper.selectList(wrapper);
    }

    public PickAlgorithm getAlgorithm(Long algorithmId) {
        PickAlgorithm algorithm = algorithmMapper.selectById(algorithmId);
        if (algorithm == null) {
            throw new BusinessException("PICK_ALGORITHM_NOT_FOUND", "抽选算法不存在：id=" + algorithmId);
        }
        return algorithm;
    }

    // ---------- 算法版本 ----------

    @Transactional
    public PickAlgorithmVersion createDraftVersion(Long algorithmId, PickAlgorithmVersionRequest request) {
        PickAlgorithm algorithm = getAlgorithm(algorithmId);
        PickParams normalized = validateParams(request.getParams());

        PickAlgorithmVersion version = new PickAlgorithmVersion();
        version.setAlgorithmId(algorithm.getId());
        version.setVersionNo(nextVersionNo(algorithm.getId()));
        version.setStatus(PaperCompConstants.VERSION_DRAFT);
        version.setParams(writeParams(normalized));
        version.setRemark(request.getRemark());
        version.setOperator(OperatorHolder.current());
        versionMapper.insert(version);
        return version;
    }

    @Transactional
    public PickAlgorithmVersion publishVersion(Long versionId) {
        PickAlgorithmVersion version = requireVersion(versionId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        if (PaperCompConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("VERSION_ALREADY_ACTIVE",
                    "版本已是生效状态，无需重复发布：" + version.getVersionNo());
        }
        if (PaperCompConstants.VERSION_DISABLED.equals(version.getStatus())) {
            throw new BusinessException("VERSION_DISABLED",
                    "停用版本不可重新发布，请新建版本：" + version.getVersionNo());
        }

        List<PickAlgorithmVersion> actives = versionMapper.selectList(new QueryWrapper<PickAlgorithmVersion>()
                .eq("algorithm_id", version.getAlgorithmId())
                .eq("status", PaperCompConstants.VERSION_ACTIVE));
        for (PickAlgorithmVersion active : actives) {
            active.setStatus(PaperCompConstants.VERSION_DISABLED);
            active.setDisabledBy(operator);
            active.setDisabledAt(now);
            versionMapper.updateById(active);
        }

        version.setStatus(PaperCompConstants.VERSION_ACTIVE);
        version.setPublishedBy(operator);
        version.setPublishedAt(now);
        versionMapper.updateById(version);
        return version;
    }

    @Transactional
    public PickAlgorithmVersion disableVersion(Long versionId) {
        PickAlgorithmVersion version = requireVersion(versionId);
        if (!PaperCompConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("VERSION_NOT_ACTIVE", "仅生效版本可停用：" + version.getVersionNo());
        }
        version.setStatus(PaperCompConstants.VERSION_DISABLED);
        version.setDisabledBy(OperatorHolder.current());
        version.setDisabledAt(LocalDateTime.now());
        versionMapper.updateById(version);
        return version;
    }

    @Transactional(readOnly = true)
    public List<PickAlgorithmVersion> listVersions(Long algorithmId) {
        getAlgorithm(algorithmId);
        return versionMapper.selectList(new QueryWrapper<PickAlgorithmVersion>()
                .eq("algorithm_id", algorithmId)
                .orderByDesc("id"));
    }

    public PickAlgorithmVersion getVersion(Long versionId) {
        return requireVersion(versionId);
    }

    /**
     * 取项目当前生效抽选算法版本：项目下所有算法中仅允许一条 ACTIVE；
     * 多条 ACTIVE 无法唯一确定时抛错；没有生效版返回 null（组卷按内置默认口径兜底）。
     */
    @Transactional(readOnly = true)
    public PickAlgorithmVersion findActiveVersion(String projectCode) {
        String project = normalizeProject(projectCode);
        List<PickAlgorithm> algorithms = algorithmMapper.selectList(
                new QueryWrapper<PickAlgorithm>().eq("project_code", project));
        if (algorithms.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (PickAlgorithm a : algorithms) {
            ids.add(a.getId());
        }
        List<PickAlgorithmVersion> actives = versionMapper.selectList(new QueryWrapper<PickAlgorithmVersion>()
                .in("algorithm_id", ids)
                .eq("status", PaperCompConstants.VERSION_ACTIVE)
                .orderByDesc("id"));
        if (actives.isEmpty()) {
            return null;
        }
        if (actives.size() > 1) {
            throw new BusinessException("MULTIPLE_ACTIVE_PICK_VERSION",
                    "项目 " + project + " 存在多个生效抽选算法版本，请先停用多余版本");
        }
        return actives.get(0);
    }

    // ---------- 参数校验 / 序列化 ----------

    public PickParams validateParams(PickParams raw) {
        PickParams p = raw == null ? new PickParams() : raw;
        PickParams norm = new PickParams();

        String strategy = p.getStrategy() == null || p.getStrategy().trim().isEmpty()
                ? PaperCompConstants.STRATEGY_KP_DIFFICULTY_QUOTA : p.getStrategy().trim().toUpperCase();
        if (!PaperCompConstants.STRATEGY_KP_DIFFICULTY_QUOTA.equals(strategy)) {
            throw new BusinessException("PICK_STRATEGY_INVALID",
                    "不支持的抽选策略：" + strategy + "，当前仅支持 KP_DIFFICULTY_QUOTA");
        }
        norm.setStrategy(strategy);

        double overlap = p.getMaxOverlapRate() == null
                ? PaperCompConstants.DEFAULT_MAX_OVERLAP_RATE : p.getMaxOverlapRate();
        if (overlap < 0d || overlap > 1d) {
            throw new BusinessException("PARAM_OVERLAP_INVALID", "套间重合率上限必须在 [0, 1] 之间");
        }
        norm.setMaxOverlapRate(overlap);

        int tries = p.getTries() == null ? PaperCompConstants.DEFAULT_TRIES : p.getTries();
        if (tries < PaperCompConstants.MIN_TRIES || tries > PaperCompConstants.MAX_TRIES) {
            throw new BusinessException("PARAM_TRIES_INVALID",
                    "试抽轮数须在 [" + PaperCompConstants.MIN_TRIES + ", " + PaperCompConstants.MAX_TRIES + "] 之间");
        }
        norm.setTries(tries);

        norm.setAllowReplacement(p.getAllowReplacement() == null
                ? PaperCompConstants.DEFAULT_ALLOW_REPLACEMENT : p.getAllowReplacement());
        return norm;
    }

    public PickParams defaultParams() {
        PickParams p = new PickParams();
        p.setStrategy(PaperCompConstants.STRATEGY_KP_DIFFICULTY_QUOTA);
        p.setMaxOverlapRate(PaperCompConstants.DEFAULT_MAX_OVERLAP_RATE);
        p.setTries(PaperCompConstants.DEFAULT_TRIES);
        p.setAllowReplacement(PaperCompConstants.DEFAULT_ALLOW_REPLACEMENT);
        return p;
    }

    public String writeParams(PickParams params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new BusinessException("PICK_PARAMS_SERIALIZE_FAILED", "抽选参数序列化失败：" + e.getMessage());
        }
    }

    public PickParams readParams(String json) {
        try {
            PickParams params = objectMapper.readValue(json, new TypeReference<PickParams>() {
            });
            if (params == null) {
                throw new BusinessException("PICK_PARAMS_BROKEN", "抽选算法版本参数为空或已损坏");
            }
            return params;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("PICK_PARAMS_BROKEN", "抽选算法版本参数无法解析：" + e.getMessage());
        }
    }

    // ---------- 内部 ----------

    private PickAlgorithmVersion requireVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException("VERSION_ID_REQUIRED", "算法版本 id 不能为空");
        }
        PickAlgorithmVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("PICK_VERSION_NOT_FOUND", "抽选算法版本不存在：id=" + versionId);
        }
        return version;
    }

    /** 版本号：算法 code + P + 递增序号（如 PICK-DEFAULT-P1）。 */
    private String nextVersionNo(Long algorithmId) {
        PickAlgorithm algorithm = getAlgorithm(algorithmId);
        int max = 0;
        String prefix = algorithm.getCode() + "-P";
        for (PickAlgorithmVersion v : versionMapper.selectList(
                new QueryWrapper<PickAlgorithmVersion>().eq("algorithm_id", algorithmId))) {
            String no = v.getVersionNo();
            if (no != null && no.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 非本算法序号忽略
                }
            }
        }
        return prefix + (max + 1);
    }

    private String normalizeProject(String projectCode) {
        if (projectCode == null || projectCode.trim().isEmpty()) {
            return PaperCompConstants.PROJECT_DEFAULT;
        }
        return projectCode.trim().toUpperCase();
    }
}
