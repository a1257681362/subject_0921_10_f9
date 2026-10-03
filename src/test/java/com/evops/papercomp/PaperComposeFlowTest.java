package com.evops.papercomp;

import com.evops.common.BusinessException;
import com.evops.papercomp.dto.ComposeRequest;
import com.evops.papercomp.dto.MaterialRequest;
import com.evops.papercomp.dto.MaterialUpdateRequest;
import com.evops.papercomp.dto.PickAlgorithmRequest;
import com.evops.papercomp.dto.PickAlgorithmVersionRequest;
import com.evops.papercomp.dto.PickParams;
import com.evops.papercomp.dto.QuotaRequest;
import com.evops.papercomp.entity.Material;
import com.evops.papercomp.entity.Paper;
import com.evops.papercomp.entity.PickAlgorithm;
import com.evops.papercomp.entity.PickAlgorithmVersion;
import com.evops.papercomp.mapper.MaterialMapper;
import com.evops.papercomp.service.ComposeService;
import com.evops.papercomp.service.MaterialService;
import com.evops.papercomp.service.PickAlgorithmService;
import com.evops.papercomp.vo.ComposePreview;
import com.evops.papercomp.vo.ComposeResultView;
import com.evops.papercomp.vo.PaperItemView;
import com.evops.papercomp.vo.PaperVerifyView;
import com.evops.papercomp.vo.PaperView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多场并行组卷端到端：
 * 算法三段式 → 素材池增删留痕 → 配比并行抽题（种子/指纹/重合率/自动重抽/池不足提示）
 * → 同种子可复算 → 素材事后增删不影响历史卷面与复算 → 卷面只读废弃留痕。
 */
@SpringBootTest
@ActiveProfiles("test")
class PaperComposeFlowTest {

    /** 每个测试方法独立项目编码（JUnit 每方法新建实例），避免同项目多生效算法互相干扰。 */
    private static final AtomicInteger SEQ = new AtomicInteger();
    private String project;

    @BeforeEach
    void setUp() {
        // 项目编码仅大写字母/数字；同时作为素材/算法 code 的全局唯一后缀
        project = "ZK" + String.format("%03d", SEQ.incrementAndGet());
    }

    @Autowired
    private ComposeService composeService;
    @Autowired
    private MaterialService materialService;
    @Autowired
    private PickAlgorithmService pickAlgorithmService;
    @Autowired
    private MaterialMapper materialMapper;

    private void seedMaterial(String code, String kp, String difficulty, String score) {
        Material m = new Material();
        m.setCode(code + "-" + project);
        m.setProjectCode(project);
        m.setKnowledgePoint(kp);
        m.setDifficulty(difficulty);
        m.setQuestionType("SINGLE");
        m.setScore(new BigDecimal(score));
        m.setStemDigest("题干-" + code);
        m.setStatus("ACTIVE");
        materialMapper.insert(m);
    }

    private QuotaRequest quota(String kp, String difficulty, int count) {
        QuotaRequest q = new QuotaRequest();
        q.setKnowledgePoint(kp);
        q.setDifficulty(difficulty);
        q.setCount(count);
        return q;
    }

    private ComposeRequest composeRequest(int paperCount, long seed, Double threshold, QuotaRequest... quotas) {
        ComposeRequest req = new ComposeRequest();
        req.setProjectCode(project);
        req.setPaperCount(paperCount);
        req.setSeed(seed);
        req.setMaxOverlapRate(threshold);
        req.setQuotas(new ArrayList<>(Arrays.asList(quotas)));
        return req;
    }

    private List<String> codesOf(PaperView paper) {
        List<String> codes = new ArrayList<>();
        for (PaperItemView item : paper.getItems()) {
            codes.add(item.getMaterialCode());
        }
        return codes;
    }

    private PickAlgorithmVersion publishAlgorithm(String code, double threshold, int tries, boolean replace) {
        PickAlgorithmRequest ar = new PickAlgorithmRequest();
        ar.setCode(code + "-" + project);
        ar.setName("抽选算法-" + code);
        ar.setProjectCode(project);
        PickAlgorithm algo = pickAlgorithmService.createAlgorithm(ar);

        PickParams params = new PickParams();
        params.setStrategy("KP_DIFFICULTY_QUOTA");
        params.setMaxOverlapRate(threshold);
        params.setTries(tries);
        params.setAllowReplacement(replace);
        PickAlgorithmVersionRequest vr = new PickAlgorithmVersionRequest();
        vr.setParams(params);
        PickAlgorithmVersion draft = pickAlgorithmService.createDraftVersion(algo.getId(), vr);
        return pickAlgorithmService.publishVersion(draft.getId());
    }

    @Test
    void algorithmModule_versionLifecycle_draftActiveDisabled() {
        String code = "ALG-LIFE-" + project;
        PickAlgorithmRequest ar = new PickAlgorithmRequest();
        ar.setCode(code);
        ar.setName("抽选算法-生命周期");
        ar.setProjectCode(project);
        PickAlgorithm algo = pickAlgorithmService.createAlgorithm(ar);
        assertNotNull(algo.getId());

        // 重复编码拒收
        PickAlgorithmRequest dup = new PickAlgorithmRequest();
        dup.setCode(code);
        dup.setName("重复");
        dup.setProjectCode(project);
        assertThrows(BusinessException.class, () -> pickAlgorithmService.createAlgorithm(dup));

        PickParams params = new PickParams();
        params.setStrategy("KP_DIFFICULTY_QUOTA");
        params.setMaxOverlapRate(0.25);
        params.setTries(100);
        params.setAllowReplacement(true);
        PickAlgorithmVersionRequest vr = new PickAlgorithmVersionRequest();
        vr.setParams(params);
        PickAlgorithmVersion draft = pickAlgorithmService.createDraftVersion(algo.getId(), vr);
        assertEquals("DRAFT", draft.getStatus());

        PickAlgorithmVersion v1 = pickAlgorithmService.publishVersion(draft.getId());
        assertEquals("ACTIVE", v1.getStatus());
        // 重复发布拒收
        assertThrows(BusinessException.class, () -> pickAlgorithmService.publishVersion(v1.getId()));

        // 同算法第二版发布：旧版自动 DISABLED，同模块同时刻仅一版 ACTIVE
        PickAlgorithmVersionRequest vr2 = new PickAlgorithmVersionRequest();
        vr2.setParams(params);
        PickAlgorithmVersion v2 = pickAlgorithmService.publishVersion(
                pickAlgorithmService.createDraftVersion(algo.getId(), vr2).getId());
        assertEquals("ACTIVE", v2.getStatus());
        assertEquals("DISABLED", pickAlgorithmService.getVersion(v1.getId()).getStatus());

        // 停用生效版；停用版不可再发布
        pickAlgorithmService.disableVersion(v2.getId());
        assertEquals("DISABLED", pickAlgorithmService.getVersion(v2.getId()).getStatus());
        assertThrows(BusinessException.class, () -> pickAlgorithmService.publishVersion(v2.getId()));

        // 非法参数拒收
        PickParams bad = new PickParams();
        bad.setStrategy("UNKNOWN");
        PickAlgorithmVersionRequest badReq = new PickAlgorithmVersionRequest();
        badReq.setParams(bad);
        assertThrows(BusinessException.class,
                () -> pickAlgorithmService.createDraftVersion(algo.getId(), badReq));
    }

    @Test
    void parallelCompose_quotaFingerprintOverlap_andReproducible() {
        // 两个知识点×两难度，每桶 6 题
        for (String kp : new String[]{"法规", "安全"}) {
            for (String diff : new String[]{"EASY", "MEDIUM"}) {
                for (int i = 1; i <= 6; i++) {
                    seedMaterial("Q-" + kp + "-" + diff + "-" + i, kp, diff, "2.00");
                }
            }
        }
        publishAlgorithm("ALG-MAIN", 0.20, 200, true);

        // 每套：法规易2 + 法规中2 + 安全易2 + 安全中2 = 8 题；并行 3 套
        ComposeRequest req = composeRequest(3, 987654321L, null,
                quota("法规", "EASY", 2), quota("法规", "MEDIUM", 2),
                quota("安全", "EASY", 2), quota("安全", "MEDIUM", 2));

        ComposePreview preview = composeService.preview(req);
        assertTrue(preview.isPoolSufficient());
        assertEquals(3, preview.getPaperCount().intValue());
        assertEquals(8, preview.getQuestionCountPerPaper().intValue());

        ComposeResultView result = composeService.compose(req);
        assertEquals("COMPOSED", result.getStatus());
        assertEquals(3, result.getGeneratedCount().intValue());
        assertEquals(3, result.getPapers().size());

        // 每套：题量正确、一套内无重复题、有指纹、每套总分 8*2=16
        Set<String> allFingerprints = new HashSet<>();
        for (PaperView p : result.getPapers()) {
            assertEquals(8, p.getQuestionCount().intValue());
            assertEquals(0, new BigDecimal("16.00").compareTo(p.getTotalScore()));
            assertEquals(64, p.getFingerprint().length());
            assertEquals(8, new HashSet<>(codesOf(p)).size());
            allFingerprints.add(p.getFingerprint());
            // 冻结了算法版本号（非内置默认）
            assertNotEquals(ComposeService.BUILTIN_VERSION_NO, result.getPickVersionNo());
        }

        // 阈值 0.2、每套 8 题 → 任意两套共享题必须为 0（交集整数 ≤1.6 即 0/1，实际压到 ≤1）
        List<PaperView> papers = result.getPapers();
        for (int i = 0; i < papers.size(); i++) {
            Set<String> a = new HashSet<>(codesOf(papers.get(i)));
            for (int j = i + 1; j < papers.size(); j++) {
                Set<String> b = new HashSet<>(codesOf(papers.get(j)));
                b.retainAll(a);
                double overlap = (double) b.size() / 8;
                assertTrue(overlap <= 0.20 + 1e-9, "套间重合率超阈：" + overlap);
            }
        }

        // 每套都能按冻结快照+种子复算通过
        for (PaperView p : papers) {
            PaperVerifyView verify = composeService.verify(p.getId());
            assertTrue(verify.isVerified(), verify.getMessage());
            assertTrue(verify.isItemsMatch());
        }

        // 同种子、同池、同配比再组一次：入选题序列与指纹必须逐套一致（抽取可复算）
        ComposeResultView again = composeService.compose(req);
        for (int i = 0; i < 3; i++) {
            assertEquals(codesOf(result.getPapers().get(i)), codesOf(again.getPapers().get(i)));
            assertEquals(result.getPapers().get(i).getFingerprint(),
                    again.getPapers().get(i).getFingerprint());
        }
    }

    @Test
    void poolInsufficient_hardRejectWithPerBucketDetail() {
        seedMaterial("P-E1", "法规", "EASY", "1.00");
        seedMaterial("P-E2", "法规", "EASY", "1.00");
        // 法规中档一题没有
        ComposeRequest req = composeRequest(2, 1L, 0.2,
                quota("法规", "EASY", 2), quota("法规", "MEDIUM", 3));

        ComposePreview preview = composeService.preview(req);
        assertFalse(preview.isPoolSufficient());
        assertFalse(preview.getWarnings().isEmpty());

        BusinessException ex = assertThrows(BusinessException.class, () -> composeService.compose(req));
        assertEquals("POOL_INSUFFICIENT", ex.getCode());
        assertTrue(ex.getMessage().contains("MEDIUM"));
    }

    @Test
    void overlapAboveThreshold_autoRedraws_partialWithTrace() {
        // 每桶仅 1 题，每套每桶抽 1 题（每套 1 题），出 2 套，阈值 0：第二套必与第一套完全重合
        seedMaterial("ONLY-1", "唯一考点", "EASY", "5.00");
        publishAlgorithm("ALG-TIGHT", 0.0, 3, true);

        ComposeRequest req = composeRequest(2, 42L, 0.0, quota("唯一考点", "EASY", 1));
        ComposeResultView result = composeService.compose(req);

        // 桶够（不触池不足硬拒收），但第二套怎么抽都重合 → PARTIAL，仅 1 套生成
        assertEquals("PARTIAL", result.getStatus());
        assertEquals(1, result.getGeneratedCount().intValue());
        assertFalse(result.getWarnings().isEmpty());

        // 试抽痕迹里第 2 套有被重合率否决的轮次（自动重抽确实发生）
        boolean hasRejectedOverlap = composeService.attempts(result.getComposeBatchId()).stream()
                .anyMatch(a -> a.getPaperIndex() == 2 && "REJECTED_OVERLAP".equals(a.getResult()));
        assertTrue(hasRejectedOverlap, "应存在因重合率超阈被否的试抽记录");
    }

    @Test
    void materialChangeAfterCompose_doesNotAffectHistoricalPaperOrRecompute() {
        seedMaterial("H-E1", "急救", "EASY", "3.00");
        seedMaterial("H-E2", "急救", "EASY", "3.00");
        seedMaterial("H-H1", "急救", "HARD", "4.00");

        ComposeRequest req = composeRequest(1, 7L, 0.5,
                quota("急救", "EASY", 2), quota("急救", "HARD", 1));
        ComposeResultView result = composeService.compose(req);
        PaperView paper = result.getPapers().get(0);
        List<String> originalCodes = codesOf(paper);

        // 事后把入选题之一改分值并停用；新增一道新题
        Material picked = materialMapper.selectList(null).stream()
                .filter(m -> project.equals(m.getProjectCode()) && originalCodes.contains(m.getCode()))
                .findFirst().orElse(null);
        assertNotNull(picked);
        BigDecimal oldScore = picked.getScore();

        MaterialRequest addReq = new MaterialRequest();
        addReq.setCode("H-NEW-" + project);
        addReq.setProjectCode(project);
        addReq.setKnowledgePoint("急救");
        addReq.setDifficulty("EASY");
        addReq.setScore(new BigDecimal("9.00"));
        Material created = materialService.create(addReq);

        MaterialUpdateRequest upd = new MaterialUpdateRequest();
        upd.setScore(new BigDecimal("1.50"));
        upd.setReason("复核调分");
        materialService.update(picked.getId(), upd);
        materialService.disable(picked.getId(), "题干勘误，停用");

        // 卷面视图仍为冻结的旧分值、旧题目集合，不含新增题
        PaperView reloaded = composeService.getPaperView(paper.getId());
        assertEquals(originalCodes, codesOf(reloaded));
        PaperItemView changedItem = reloaded.getItems().stream()
                .filter(i -> i.getMaterialCode().equals(picked.getCode())).findFirst().orElse(null);
        assertNotNull(changedItem);
        assertEquals(0, oldScore.compareTo(changedItem.getScore()));
        assertFalse(codesOf(reloaded).contains("H-NEW-" + project));

        // 按组卷时刻冻结池快照+种子复算，仍然一致（素材池已变也不影响）
        PaperVerifyView verify = composeService.verify(paper.getId());
        assertTrue(verify.isVerified(), verify.getMessage());

        // 素材留痕：新增 ADD、分值 UPDATE、停用 DISABLE 都在
        long changeKinds = materialService.changes(picked.getId()).stream()
                .filter(c -> "UPDATE".equals(c.getChangeType()) || "DISABLE".equals(c.getChangeType()))
                .count();
        assertTrue(changeKinds >= 2);
        assertEquals(1, materialService.changes(created.getId()).size());
    }

    @Test
    void paperIsReadOnly_voidLeavesRecord_andDuplicateVoidRejected() {
        seedMaterial("V-E1", "废弃考点", "EASY", "2.00");
        ComposeRequest req = composeRequest(1, 11L, 0.5, quota("废弃考点", "EASY", 1));
        PaperView paper = composeService.compose(req).getPapers().get(0);

        Paper voided = composeService.voidPaper(paper.getId(), "卷面配比有误，作废重出");
        assertEquals("VOID", voided.getStatus());
        assertEquals(1, composeService.voidRecords(paper.getId()).size());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> composeService.voidPaper(paper.getId(), "再次废弃"));
        assertEquals("PAPER_ALREADY_VOID", ex.getCode());

        // 废弃后复算仍能证明该卷生成时的公平性（只读留痕，不销毁证据）
        assertTrue(composeService.verify(paper.getId()).isVerified());
    }

    @Test
    void versionUpgrade_freezesVersionNoOnPapers() {
        seedMaterial("U-E1", "升级考点", "EASY", "2.00");
        seedMaterial("U-E2", "升级考点", "EASY", "2.00");

        PickAlgorithmVersion v1 = publishAlgorithm("ALG-UPG", 0.5, 50, true);

        PickAlgorithm algo = pickAlgorithmService.listAlgorithms(project)
                .stream().filter(a -> a.getCode().equals("ALG-UPG-" + project)).findFirst().orElse(null);
        assertNotNull(algo);

        ComposeRequest req1 = composeRequest(1, 5L, null, quota("升级考点", "EASY", 1));
        String noV1 = composeService.compose(req1).getPickVersionNo();
        assertEquals(v1.getVersionNo(), noV1);

        // 发布 v2（阈值更严），v1 自动 DISABLED；新卷面冻结 v2
        PickParams params2 = new PickParams();
        params2.setStrategy("KP_DIFFICULTY_QUOTA");
        params2.setMaxOverlapRate(0.1);
        params2.setTries(30);
        params2.setAllowReplacement(true);
        PickAlgorithmVersionRequest vr2 = new PickAlgorithmVersionRequest();
        vr2.setParams(params2);
        PickAlgorithmVersion v2 = pickAlgorithmService.publishVersion(
                pickAlgorithmService.createDraftVersion(algo.getId(), vr2).getId());
        assertEquals("DISABLED", pickAlgorithmService.getVersion(v1.getId()).getStatus());
        assertEquals("ACTIVE", pickAlgorithmService.getVersion(v2.getId()).getStatus());

        String noV2 = composeService.compose(req1).getPickVersionNo();
        assertEquals(v2.getVersionNo(), noV2);
        assertNotEquals(noV1, noV2);
    }
}
