package com.evops.papercomp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.papercomp.common.PaperCompConstants;
import com.evops.papercomp.dto.ComposeRequest;
import com.evops.papercomp.dto.PickParams;
import com.evops.papercomp.dto.QuotaRequest;
import com.evops.papercomp.engine.Fingerprints;
import com.evops.papercomp.engine.PickEngine;
import com.evops.papercomp.entity.ComposeAttempt;
import com.evops.papercomp.entity.ComposeBatch;
import com.evops.papercomp.entity.Material;
import com.evops.papercomp.entity.Paper;
import com.evops.papercomp.entity.PaperItem;
import com.evops.papercomp.entity.PaperVoidRecord;
import com.evops.papercomp.entity.PickAlgorithmVersion;
import com.evops.papercomp.mapper.ComposeAttemptMapper;
import com.evops.papercomp.mapper.ComposeBatchMapper;
import com.evops.papercomp.mapper.PaperItemMapper;
import com.evops.papercomp.mapper.PaperMapper;
import com.evops.papercomp.mapper.PaperVoidRecordMapper;
import com.evops.papercomp.vo.ComposePreview;
import com.evops.papercomp.vo.ComposeResultView;
import com.evops.papercomp.vo.PaperItemView;
import com.evops.papercomp.vo.PaperVerifyView;
import com.evops.papercomp.vo.PaperView;
import com.evops.papercomp.vo.QuotaAvailability;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多场并行组卷：
 * - 按知识点×难度档配比，从素材池（仅 ACTIVE）确定性随机抽题，一次并行出多套；
 * - 抽选算法取项目当前 ACTIVE 版本（无则内置默认），版本号/参数/种子/池快照全部冻结留档；
 * - 套间重合率超阈自动换种子重抽（全程试抽留痕）；桶题量不足整批拒收并逐桶提示；
 *   桶够但多套压不下重合率时部分生成（PARTIAL），缺套待补素材后另组；
 * - 每套存卷面指纹；复算用组卷时刻冻结的池快照+种子，素材池事后增删不影响历史卷面与复算；
 * - 卷面生成即只读，纠错走废弃（留记录）后另组新卷。
 */
@Service
public class ComposeService {

    /** 无生效算法版本时结果中展示的口径。 */
    public static final String BUILTIN_VERSION_NO = "BUILTIN_DEFAULT";

    private static final DateTimeFormatter BATCH_TS = DateTimeFormatter.ofPattern("yyMMddHHmm");

    private final ComposeBatchMapper batchMapper;
    private final PaperMapper paperMapper;
    private final PaperItemMapper paperItemMapper;
    private final ComposeAttemptMapper attemptMapper;
    private final PaperVoidRecordMapper voidMapper;
    private final MaterialService materialService;
    private final PickAlgorithmService pickAlgorithmService;
    private final ExamBatchMapper examBatchMapper;
    private final ObjectMapper objectMapper;

    public ComposeService(ComposeBatchMapper batchMapper,
                          PaperMapper paperMapper,
                          PaperItemMapper paperItemMapper,
                          ComposeAttemptMapper attemptMapper,
                          PaperVoidRecordMapper voidMapper,
                          MaterialService materialService,
                          PickAlgorithmService pickAlgorithmService,
                          ExamBatchMapper examBatchMapper,
                          ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.paperMapper = paperMapper;
        this.paperItemMapper = paperItemMapper;
        this.attemptMapper = attemptMapper;
        this.voidMapper = voidMapper;
        this.materialService = materialService;
        this.pickAlgorithmService = pickAlgorithmService;
        this.examBatchMapper = examBatchMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 预检 ----------

    @Transactional(readOnly = true)
    public ComposePreview preview(ComposeRequest request) {
        Spec spec = buildSpec(request);

        List<QuotaAvailability> availabilities = new ArrayList<>();
        boolean sufficient = true;
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> availByBucket = countByBucket(spec.pool);
        for (PickEngine.Quota q : spec.quotas) {
            int available = availByBucket.getOrDefault(q.key(), 0);
            QuotaAvailability a = new QuotaAvailability();
            a.setKnowledgePoint(q.knowledgePoint);
            a.setDifficulty(q.difficulty);
            a.setRequired(q.count);
            a.setAvailable(available);
            a.setSufficient(available >= q.count);
            if (!a.isSufficient()) {
                sufficient = false;
                warnings.add(String.format("素材池不足：知识点[%s] 难度档[%s] 需 %d 题，当前可用仅 %d 题",
                        q.knowledgePoint, q.difficulty, q.count, available));
            }
            availabilities.add(a);
        }

        ComposePreview preview = new ComposePreview();
        preview.setProjectCode(spec.project);
        preview.setExamBatchId(request.getExamBatchId());
        preview.setPaperCount(request.getPaperCount());
        preview.setQuestionCountPerPaper(spec.questionCountPerPaper);
        preview.setPickVersionNo(spec.versionNo);
        preview.setBaseSeed(spec.baseSeed);
        preview.setMaxOverlapRate(rate(spec.params.maxOverlapRate));
        preview.setAvailabilities(availabilities);
        preview.setPoolSufficient(sufficient);
        preview.setWarnings(warnings);

        if (sufficient) {
            // 池够才试算：预检按"将使用的同一冻结输入"试跑，预演重合率/重抽/部分生成（不落库）
            PickEngine.ComposeResult trial = PickEngine.compose(
                    spec.pool, spec.quotas, spec.params, spec.baseSeed, request.getPaperCount());
            preview.getWarnings().addAll(trial.warnings);
            preview.setSummary(String.format(
                    "拟并行组卷 %d 套（每套 %d 题），抽选版本 %s，基础种子 %d，阈值 %.0f%%；"
                            + "试算可生成 %d 套，最大套间重合率 %.2f%%，试抽 %d 次",
                    request.getPaperCount(), spec.questionCountPerPaper, spec.versionNo, spec.baseSeed,
                    spec.params.maxOverlapRate * 100, trial.generatedCount,
                    trial.maxActualOverlapRate * 100, trial.attempts.size()));
        } else {
            preview.setSummary("素材池不足，无法组卷：请先按提示补充题目后再执行");
        }
        return preview;
    }

    // ---------- 执行 ----------

    @Transactional
    public ComposeResultView compose(ComposeRequest request) {
        Spec spec = buildSpec(request);

        // 硬约束：任一配比桶题量不足 → 整批拒收、不落半套，逐桶给明细
        Map<String, Integer> availByBucket = countByBucket(spec.pool);
        List<String> shortageMessages = new ArrayList<>();
        for (PickEngine.Quota q : spec.quotas) {
            int available = availByBucket.getOrDefault(q.key(), 0);
            if (available < q.count) {
                shortageMessages.add(String.format("知识点[%s] 难度档[%s] 需 %d 题，可用仅 %d 题",
                        q.knowledgePoint, q.difficulty, q.count, available));
            }
        }
        if (!shortageMessages.isEmpty()) {
            throw new BusinessException("POOL_INSUFFICIENT",
                    "素材池不足，整批拒收（未落任何卷面）：" + String.join("；", shortageMessages));
        }

        PickEngine.ComposeResult result = PickEngine.compose(
                spec.pool, spec.quotas, spec.params, spec.baseSeed, request.getPaperCount());

        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        ComposeBatch batch = new ComposeBatch();
        batch.setBatchNo(nextBatchNo(now));
        batch.setProjectCode(spec.project);
        batch.setExamBatchId(request.getExamBatchId());
        batch.setPaperCount(request.getPaperCount());
        batch.setQuestionCountPerPaper(spec.questionCountPerPaper);
        batch.setPickVersionId(spec.version == null ? null : spec.version.getId());
        batch.setPickVersionNo(spec.versionNo);
        batch.setBaseSeed(spec.baseSeed);
        batch.setQuotas(writeJson(spec.quotas));
        batch.setPoolSnapshot(writeJson(spec.pool));
        batch.setPoolSnapshotFingerprint(spec.poolFingerprint);
        batch.setMaxOverlapRate(rate(spec.params.maxOverlapRate));
        batch.setGeneratedCount(result.generatedCount);
        batch.setMaxActualOverlapRate(rate(result.maxActualOverlapRate));
        batch.setWarning(result.warnings.isEmpty() ? null : String.join("；", result.warnings));
        batch.setStatus(result.generatedCount == request.getPaperCount()
                ? PaperCompConstants.BATCH_COMPOSED : PaperCompConstants.BATCH_PARTIAL);
        batch.setOperator(operator);
        batch.setRemark(request.getRemark());
        batch.setComposedAt(now);
        batchMapper.insert(batch);

        // 试抽痕迹（含被否轮次）先落库，证明超阈自动重抽确实发生
        for (PickEngine.AttemptTrace trace : result.attempts) {
            ComposeAttempt attempt = new ComposeAttempt();
            attempt.setComposeBatchId(batch.getId());
            attempt.setPaperIndex(trace.paperIndex);
            attempt.setAttemptNo(trace.attemptNo);
            attempt.setSeed(trace.seed);
            attempt.setPickedCodes(writeJson(trace.pickedCodes));
            attempt.setOverlapRate(rate(trace.overlapRate));
            attempt.setResult(trace.result);
            attemptMapper.insert(attempt);
        }

        List<PaperView> paperViews = new ArrayList<>();
        for (PickEngine.PaperPick pick : result.papers) {
            if (!pick.accepted) {
                continue;
            }
            Paper paper = new Paper();
            paper.setComposeBatchId(batch.getId());
            paper.setBatchNo(batch.getBatchNo());
            paper.setProjectCode(spec.project);
            paper.setPaperIndex(pick.paperIndex);
            paper.setPaperNo(batch.getBatchNo() + "-" + String.format("%02d", pick.paperIndex));
            paper.setSeed(pick.seed);
            paper.setAttemptNo(pick.attemptNo);
            paper.setQuestionCount(pick.items.size());
            paper.setTotalScore(sumScore(pick.items));
            paper.setMaxOverlapRate(rate(pick.maxOverlapRate));
            paper.setPoolSnapshotFingerprint(spec.poolFingerprint);
            paper.setMaterialSnapshot(writeJson(pick.items));
            paper.setStatus(PaperCompConstants.PAPER_ACTIVE);
            paper.setOperator(operator);
            paper.setComposedAt(now);
            paper.setFingerprint(paperFingerprint(spec.versionNo, spec.poolFingerprint, spec.baseSeed,
                    pick.paperIndex, pick.seed, pick.attemptNo, pick.items));
            paperMapper.insert(paper);

            int seq = 1;
            for (PickEngine.MaterialSnapshot item : pick.items) {
                PaperItem row = new PaperItem();
                row.setPaperId(paper.getId());
                row.setSeqNo(seq++);
                row.setMaterialId(item.materialId);
                row.setMaterialCode(item.code);
                row.setKnowledgePoint(item.knowledgePoint);
                row.setDifficulty(item.difficulty);
                row.setQuestionType(item.questionType);
                row.setScore(item.score);
                row.setStemDigest(item.stemDigest);
                paperItemMapper.insert(row);
            }
            paperViews.add(toView(paper, pick.items));
        }

        ComposeResultView view = new ComposeResultView();
        view.setComposeBatchId(batch.getId());
        view.setBatchNo(batch.getBatchNo());
        view.setProjectCode(spec.project);
        view.setPaperCount(request.getPaperCount());
        view.setGeneratedCount(result.generatedCount);
        view.setQuestionCountPerPaper(spec.questionCountPerPaper);
        view.setPickVersionNo(spec.versionNo);
        view.setBaseSeed(spec.baseSeed);
        view.setMaxOverlapRate(rate(spec.params.maxOverlapRate));
        view.setMaxActualOverlapRate(rate(result.maxActualOverlapRate));
        view.setStatus(batch.getStatus());
        view.setWarnings(result.warnings);
        view.setPapers(paperViews);
        view.setSummary(String.format("组卷批号 %s：成功 %d/%d 套，最大套间重合率 %.2f%%（阈值 %.0f%%），基础种子 %d，状态 %s",
                batch.getBatchNo(), result.generatedCount, request.getPaperCount(),
                result.maxActualOverlapRate * 100, spec.params.maxOverlapRate * 100,
                spec.baseSeed, batch.getStatus()));
        return view;
    }

    // ---------- 公平性复算 ----------

    /**
     * 用组卷时刻冻结的池快照+配比+种子重放抽题，比对入选题与卷面指纹：
     * 一致即"同输入必得同卷面"，抽取过程可复算、卷面未被篡改；全程不读当前素材池。
     */
    @Transactional(readOnly = true)
    public PaperVerifyView verify(Long paperId) {
        Paper paper = requirePaper(paperId);
        ComposeBatch batch = requireBatch(paper.getComposeBatchId());

        List<PickEngine.MaterialSnapshot> frozenPool = readList(batch.getPoolSnapshot(),
                new TypeReference<List<PickEngine.MaterialSnapshot>>() {
                }, "POOL_SNAPSHOT_BROKEN", "素材池快照");
        List<PickEngine.Quota> frozenQuotas = readList(batch.getQuotas(),
                new TypeReference<List<PickEngine.Quota>>() {
                }, "QUOTAS_BROKEN", "配比清单");

        List<PickEngine.MaterialSnapshot> redrawn =
                PickEngine.drawFromSeed(frozenPool, frozenQuotas, paper.getSeed());

        List<String> recomputedCodes = new ArrayList<>();
        for (PickEngine.MaterialSnapshot m : redrawn) {
            recomputedCodes.add(m.code);
        }
        List<String> storedCodes = new ArrayList<>();
        List<PaperItem> storedItems = paperItemMapper.selectList(new QueryWrapper<PaperItem>()
                .eq("paper_id", paper.getId()).orderByAsc("seq_no"));
        for (PaperItem item : storedItems) {
            storedCodes.add(item.getMaterialCode());
        }
        boolean itemsMatch = storedCodes.equals(recomputedCodes);

        String recomputedFp = paperFingerprint(batch.getPickVersionNo(), batch.getPoolSnapshotFingerprint(),
                batch.getBaseSeed(), paper.getPaperIndex(), paper.getSeed(), paper.getAttemptNo(), redrawn);
        boolean fpMatch = recomputedFp.equals(paper.getFingerprint());

        PaperVerifyView view = new PaperVerifyView();
        view.setPaperNo(paper.getPaperNo());
        view.setStoredFingerprint(paper.getFingerprint());
        view.setRecomputedFingerprint(recomputedFp);
        view.setItemsMatch(itemsMatch);
        view.setVerified(itemsMatch && fpMatch);
        view.setSeed(paper.getSeed());
        view.setPickVersionNo(batch.getPickVersionNo());
        view.setPoolSnapshotFingerprint(batch.getPoolSnapshotFingerprint());
        view.setMessage(view.isVerified()
                ? "复算一致：按组卷时刻冻结的素材池快照、配比与种子重放，入选题与卷面指纹完全一致"
                : "复算不一致：入选题或指纹与存档不符，卷面可能被改动或冻结数据损坏");
        return view;
    }

    // ---------- 卷面废弃（只读，纠错走废弃） ----------

    @Transactional
    public Paper voidPaper(Long paperId, String reason) {
        Paper paper = requirePaper(paperId);
        if (PaperCompConstants.PAPER_VOID.equals(paper.getStatus())) {
            throw new BusinessException("PAPER_ALREADY_VOID", "卷面已废弃，无需重复废弃：" + paper.getPaperNo());
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("PAPER_VOID_REASON_REQUIRED", "废弃原因不能为空");
        }
        paper.setStatus(PaperCompConstants.PAPER_VOID);
        paperMapper.updateById(paper);

        PaperVoidRecord record = new PaperVoidRecord();
        record.setPaperId(paper.getId());
        record.setPaperNo(paper.getPaperNo());
        record.setReason(reason.trim());
        record.setOperator(OperatorHolder.current());
        record.setVoidedAt(LocalDateTime.now());
        voidMapper.insert(record);
        return paper;
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public List<ComposeBatch> listBatches(String projectCode, Long examBatchId) {
        QueryWrapper<ComposeBatch> wrapper = new QueryWrapper<ComposeBatch>()
                .orderByDesc("id");
        if (projectCode != null && !projectCode.trim().isEmpty()) {
            wrapper.eq("project_code", projectCode.trim().toUpperCase());
        }
        if (examBatchId != null) {
            wrapper.eq("exam_batch_id", examBatchId);
        }
        List<ComposeBatch> batches = batchMapper.selectList(wrapper);
        // 列表不带池快照/配比两个 CLOB 大字段
        for (ComposeBatch b : batches) {
            b.setPoolSnapshot(null);
            b.setQuotas(null);
        }
        return batches;
    }

    @Transactional(readOnly = true)
    public ComposeBatch requireBatch(Long batchId) {
        ComposeBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("COMPOSE_BATCH_NOT_FOUND", "组卷批次不存在：id=" + batchId);
        }
        return batch;
    }

    @Transactional(readOnly = true)
    public List<PaperView> papersOfBatch(Long batchId) {
        requireBatch(batchId);
        List<PaperView> views = new ArrayList<>();
        for (Paper paper : paperMapper.selectList(new QueryWrapper<Paper>()
                .eq("compose_batch_id", batchId).orderByAsc("paper_index"))) {
            views.add(toView(paper, null));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public PaperView getPaperView(Long paperId) {
        Paper paper = requirePaper(paperId);
        List<PickEngine.MaterialSnapshot> items = readList(paper.getMaterialSnapshot(),
                new TypeReference<List<PickEngine.MaterialSnapshot>>() {
                }, "MATERIAL_SNAPSHOT_BROKEN", "卷面素材快照");
        return toView(paper, items);
    }

    @Transactional(readOnly = true)
    public List<ComposeAttempt> attempts(Long batchId) {
        requireBatch(batchId);
        return attemptMapper.selectList(new QueryWrapper<ComposeAttempt>()
                .eq("compose_batch_id", batchId)
                .orderByAsc("paper_index", "attempt_no"));
    }

    @Transactional(readOnly = true)
    public List<PaperVoidRecord> voidRecords(Long paperId) {
        requirePaper(paperId);
        return voidMapper.selectList(new QueryWrapper<PaperVoidRecord>()
                .eq("paper_id", paperId).orderByAsc("id"));
    }

    // ---------- 规格构建（预检/执行共用，保证口径一致） ----------

    private Spec buildSpec(ComposeRequest request) {
        if (request.getPaperCount() == null || request.getPaperCount() < 1) {
            throw new BusinessException("PAPER_COUNT_INVALID", "组卷套数必须 ≥ 1");
        }
        String project = request.getProjectCode() == null || request.getProjectCode().trim().isEmpty()
                ? PaperCompConstants.PROJECT_DEFAULT : request.getProjectCode().trim().toUpperCase();

        if (request.getExamBatchId() != null) {
            ExamBatch examBatch = examBatchMapper.selectById(request.getExamBatchId());
            if (examBatch == null) {
                throw new BusinessException("EXAM_BATCH_NOT_FOUND",
                        "关联考试批次不存在：id=" + request.getExamBatchId());
            }
        }

        List<PickEngine.Quota> quotas = normalizeQuotas(request.getQuotas());
        int questionCountPerPaper = 0;
        for (PickEngine.Quota q : quotas) {
            questionCountPerPaper += q.count;
        }

        PickAlgorithmVersion version = pickAlgorithmService.findActiveVersion(project);
        PickParams params = version == null
                ? pickAlgorithmService.defaultParams()
                : pickAlgorithmService.readParams(version.getParams());
        if (request.getMaxOverlapRate() != null) {
            double override = request.getMaxOverlapRate();
            if (override < 0d || override > 1d) {
                throw new BusinessException("PARAM_OVERLAP_INVALID", "套间重合率上限必须在 [0, 1] 之间");
            }
            params.setMaxOverlapRate(override);
        }
        String versionNo = version == null ? BUILTIN_VERSION_NO : version.getVersionNo();

        List<Material> active = materialService.activePool(project);
        List<PickEngine.MaterialSnapshot> pool = new ArrayList<>();
        for (Material m : active) {
            pool.add(new PickEngine.MaterialSnapshot(m.getId(), m.getCode(), m.getKnowledgePoint(),
                    m.getDifficulty(), m.getQuestionType(), m.getScore(), m.getStemDigest()));
        }
        String poolFingerprint = poolFingerprint(pool);

        long baseSeed = resolveSeed(request.getSeed(), project, request.getPaperCount(),
                questionCountPerPaper, poolFingerprint);

        Spec spec = new Spec();
        spec.project = project;
        spec.quotas = quotas;
        spec.questionCountPerPaper = questionCountPerPaper;
        spec.version = version;
        spec.versionNo = versionNo;
        spec.params = toEngineParams(params);
        spec.pool = pool;
        spec.poolFingerprint = poolFingerprint;
        spec.baseSeed = baseSeed;
        return spec;
    }

    private List<PickEngine.Quota> normalizeQuotas(List<QuotaRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException("QUOTAS_REQUIRED", "配比清单不能为空");
        }
        List<PickEngine.Quota> quotas = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (QuotaRequest q : raw) {
            if (q.getKnowledgePoint() == null || q.getKnowledgePoint().trim().isEmpty()) {
                throw new BusinessException("QUOTA_KP_REQUIRED", "配比知识点不能为空");
            }
            if (q.getDifficulty() == null || q.getDifficulty().trim().isEmpty()) {
                throw new BusinessException("QUOTA_DIFFICULTY_REQUIRED", "配比难度档不能为空");
            }
            if (q.getCount() == null || q.getCount() < 1) {
                throw new BusinessException("QUOTA_COUNT_INVALID", "每个配比桶题量至少 1 题");
            }
            PickEngine.Quota quota = new PickEngine.Quota(
                    q.getKnowledgePoint().trim(), q.getDifficulty().trim().toUpperCase(), q.getCount());
            if (!seen.add(quota.key())) {
                throw new BusinessException("QUOTA_DUPLICATED",
                        "配比重复：知识点[" + quota.knowledgePoint + "] 难度档[" + quota.difficulty + "] 出现多次");
            }
            quotas.add(quota);
        }
        return quotas;
    }

    private PickEngine.PickParams toEngineParams(PickParams p) {
        PickEngine.PickParams e = new PickEngine.PickParams();
        e.strategy = p.getStrategy();
        e.maxOverlapRate = p.getMaxOverlapRate();
        e.tries = p.getTries();
        e.allowReplacement = Boolean.TRUE.equals(p.getAllowReplacement());
        return e;
    }

    private Map<String, Integer> countByBucket(List<PickEngine.MaterialSnapshot> pool) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PickEngine.MaterialSnapshot m : pool) {
            String key = m.knowledgePoint + "|" + m.difficulty;
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 基础种子：显式优先；否则按 项目+套数+每套题量+池指纹+历史组卷批次数 取确定性种子。
     * 同池同配比同套数、且历史批次数未变（预检→执行紧邻）时两次一致；显式传种子可随时复现。
     */
    private long resolveSeed(Long requested, String project, int paperCount, int questionsPerPaper,
                             String poolFingerprint) {
        if (requested != null) {
            return requested;
        }
        long priorBatches = batchMapper.selectCount(null);
        String basis = project + "|" + paperCount + "|" + questionsPerPaper + "|"
                + poolFingerprint + "|" + priorBatches;
        return fnv1a64(basis);
    }

    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    // ---------- 指纹 ----------

    /** 素材池快照指纹：按 code 排序后的规范化逐行哈希，组卷与复算用同一份冻结快照。 */
    public static String poolFingerprint(List<PickEngine.MaterialSnapshot> pool) {
        List<PickEngine.MaterialSnapshot> ordered = new ArrayList<>(pool);
        ordered.sort((a, b) -> a.code.compareTo(b.code));
        StringBuilder sb = new StringBuilder("POOL|v1\n");
        for (PickEngine.MaterialSnapshot m : ordered) {
            sb.append(m.code).append('|')
                    .append(m.knowledgePoint).append('|')
                    .append(m.difficulty).append('|')
                    .append(m.questionType == null ? "" : m.questionType).append('|')
                    .append(canonicalScore(m.score)).append('|')
                    .append(m.stemDigest == null ? "" : m.stemDigest).append('\n');
        }
        return Fingerprints.sha256Hex(sb.toString());
    }

    /** 卷面指纹：绑死 算法版本+池快照指纹+基础种子+套序号+实际试抽种子/轮次+有序入选题（含属性）。 */
    public static String paperFingerprint(String versionNo, String poolFingerprint, long baseSeed,
                                          int paperIndex, long attemptSeed, int attemptNo,
                                          List<PickEngine.MaterialSnapshot> items) {
        StringBuilder sb = new StringBuilder("PAPER|v1\n");
        sb.append(versionNo).append('\n')
                .append(poolFingerprint).append('\n')
                .append(baseSeed).append('\n')
                .append(paperIndex).append('\n')
                .append(attemptSeed).append('\n')
                .append(attemptNo).append('\n');
        for (PickEngine.MaterialSnapshot m : items) {
            sb.append(m.code).append('|')
                    .append(m.knowledgePoint).append('|')
                    .append(m.difficulty).append('|')
                    .append(m.questionType == null ? "" : m.questionType).append('|')
                    .append(canonicalScore(m.score)).append('\n');
        }
        return Fingerprints.sha256Hex(sb.toString());
    }

    private static String canonicalScore(BigDecimal score) {
        if (score == null) {
            return "0.00";
        }
        return score.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ---------- 杂项 ----------

    private BigDecimal sumScore(List<PickEngine.MaterialSnapshot> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (PickEngine.MaterialSnapshot m : items) {
            if (m.score != null) {
                total = total.add(m.score);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private Paper requirePaper(Long paperId) {
        if (paperId == null) {
            throw new BusinessException("PAPER_ID_REQUIRED", "卷面 id 不能为空");
        }
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BusinessException("PAPER_NOT_FOUND", "卷面不存在：id=" + paperId);
        }
        return paper;
    }

    private PaperView toView(Paper paper, List<PickEngine.MaterialSnapshot> items) {
        PaperView v = new PaperView();
        v.setId(paper.getId());
        v.setComposeBatchId(paper.getComposeBatchId());
        v.setBatchNo(paper.getBatchNo());
        v.setProjectCode(paper.getProjectCode());
        v.setPaperIndex(paper.getPaperIndex());
        v.setPaperNo(paper.getPaperNo());
        v.setSeed(paper.getSeed());
        v.setAttemptNo(paper.getAttemptNo());
        v.setQuestionCount(paper.getQuestionCount());
        v.setTotalScore(paper.getTotalScore());
        v.setMaxOverlapRate(paper.getMaxOverlapRate());
        v.setFingerprint(paper.getFingerprint());
        v.setPoolSnapshotFingerprint(paper.getPoolSnapshotFingerprint());
        v.setStatus(paper.getStatus());
        v.setOperator(paper.getOperator());
        v.setComposedAt(paper.getComposedAt() == null ? null : paper.getComposedAt().toString());
        if (items != null) {
            List<PaperItemView> itemViews = new ArrayList<>();
            int seq = 1;
            for (PickEngine.MaterialSnapshot m : items) {
                PaperItemView iv = new PaperItemView();
                iv.setSeqNo(seq++);
                iv.setMaterialId(m.materialId);
                iv.setMaterialCode(m.code);
                iv.setKnowledgePoint(m.knowledgePoint);
                iv.setDifficulty(m.difficulty);
                iv.setQuestionType(m.questionType);
                iv.setScore(m.score);
                iv.setStemDigest(m.stemDigest);
                itemViews.add(iv);
            }
            v.setItems(itemViews);
        }
        return v;
    }

    private String nextBatchNo(LocalDateTime now) {
        String prefix = "J" + now.format(BATCH_TS);
        int max = 0;
        for (ComposeBatch b : batchMapper.selectList(new QueryWrapper<ComposeBatch>()
                .likeRight("batch_no", prefix))) {
            String no = b.getBatchNo();
            if (no != null && no.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 非本分钟序号忽略
                }
            }
        }
        return prefix + String.format("%02d", max + 1);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException("PAPERCOMP_JSON_SERIALIZE_FAILED", "组卷数据序列化失败：" + e.getMessage());
        }
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type, String code, String label) {
        try {
            List<T> list = objectMapper.readValue(json, type);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            throw new BusinessException(code, label + "无法解析：" + e.getMessage());
        }
    }

    /** 组卷规格（内部传输）。 */
    private static class Spec {
        private String project;
        private List<PickEngine.Quota> quotas;
        private int questionCountPerPaper;
        private PickAlgorithmVersion version;
        private String versionNo;
        private PickEngine.PickParams params;
        private List<PickEngine.MaterialSnapshot> pool;
        private String poolFingerprint;
        private long baseSeed;
    }
}
