package com.evops.papercomp.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 多场并行抽题引擎（纯函数式、可复算）。
 *
 * 输入：素材池快照、知识点×难度档配比、抽选参数、基础随机种子、套数；
 * 输出：每套入选题（有序）、派生种子、试抽轮次、套间重合率，以及全部试抽痕迹。
 *
 * 确定性约定（复算必须逐位一致）：
 * - 只用 {@link Random}（与混编引擎一致），不读时钟、不读库；
 * - 套 i（0 起）第 a 轮（0 轮为首轮）试抽种子：
 *   attemptSeed = baseSeed + GOLDEN1*(i+1) + GOLDEN2*(a+1)；
 * - 每个 (知识点,难度档) 桶内用该轮 Random 洗牌后取前 count 题；
 * - 素材池先按 code 排序入桶，保证同样快照遍历顺序一致。
 *
 * 公平约束：
 * - 逐套生成，后套与每个已采纳套比重合率（共享题数 / 单套题量），取最大值；
 * - 超阈自动换下一轮种子重抽；达到 tries 仍不达标判该套失败（不收录超阈卷）；
 * - 任一配比桶题量不足（可用 &lt; 需求）属硬约束，直接判池不足，给出 知识点/难度/需求/可用 明细。
 */
public final class PickEngine {

    private static final long GOLDEN1 = 0x9E3779B97F4A7C15L;
    private static final long GOLDEN2 = 0xD1B54A32D192ED03L;

    private PickEngine() {
    }

    /** 素材快照行：组卷时刻冻结，事后素材主表增删不影响复算。 */
    public static class MaterialSnapshot {
        public Long materialId;
        public String code;
        public String knowledgePoint;
        public String difficulty;
        public String questionType;
        public BigDecimal score;
        public String stemDigest;

        public MaterialSnapshot() {
        }

        public MaterialSnapshot(Long materialId, String code, String knowledgePoint, String difficulty,
                                String questionType, BigDecimal score, String stemDigest) {
            this.materialId = materialId;
            this.code = code;
            this.knowledgePoint = knowledgePoint;
            this.difficulty = difficulty;
            this.questionType = questionType;
            this.score = score;
            this.stemDigest = stemDigest;
        }
    }

    /** 配比桶：某知识点某难度档抽 count 题。 */
    public static class Quota {
        public String knowledgePoint;
        public String difficulty;
        public int count;

        public Quota() {
        }

        public Quota(String knowledgePoint, String difficulty, int count) {
            this.knowledgePoint = knowledgePoint;
            this.difficulty = difficulty;
            this.count = count;
        }

        public String key() {
            return knowledgePoint + "|" + difficulty;
        }
    }

    /** 抽选参数（发布版本时冻结）。 */
    public static class PickParams {
        public String strategy;
        public double maxOverlapRate;
        public int tries;
        /** false：首轮超阈即判失败不重抽；true：允许换种子重抽替换超阈题目。 */
        public boolean allowReplacement;
    }

    /** 桶题量不足明细。 */
    public static class Shortage {
        public String knowledgePoint;
        public String difficulty;
        public int required;
        public int available;

        public Shortage(String knowledgePoint, String difficulty, int required, int available) {
            this.knowledgePoint = knowledgePoint;
            this.difficulty = difficulty;
            this.required = required;
            this.available = available;
        }
    }

    /** 单套抽题结果。 */
    public static class PaperPick {
        public int paperIndex;
        public long seed;
        public int attemptNo;
        public List<MaterialSnapshot> items = new ArrayList<>();
        /** 与已采纳套的最大重合率（采纳时为达标值）。 */
        public double maxOverlapRate;
        public boolean accepted;
        /** POOL_SHORTAGE / OVERLAP_UNRESOLVED */
        public String failReason;

        public int questionSize() {
            return items.size();
        }
    }

    /** 一次试抽痕迹（自动重抽确实发生、为何被否，全程可查）。 */
    public static class AttemptTrace {
        public int paperIndex;
        public int attemptNo;
        public long seed;
        public List<String> pickedCodes = new ArrayList<>();
        public double overlapRate;
        /** ACCEPTED / REJECTED_OVERLAP / REJECTED_POOL */
        public String result;

        public AttemptTrace(int paperIndex, int attemptNo, long seed, List<String> pickedCodes,
                            double overlapRate, String result) {
            this.paperIndex = paperIndex;
            this.attemptNo = attemptNo;
            this.seed = seed;
            this.pickedCodes = pickedCodes;
            this.overlapRate = overlapRate;
            this.result = result;
        }
    }

    /** 一次多套并行抽题的总结果。 */
    public static class ComposeResult {
        public List<PaperPick> papers = new ArrayList<>();
        public List<AttemptTrace> attempts = new ArrayList<>();
        public List<Shortage> shortages = new ArrayList<>();
        public int requestedCount;
        public int generatedCount;
        public double maxActualOverlapRate;
        public boolean feasible;
        public List<String> warnings = new ArrayList<>();
    }

    /**
     * 执行多套抽题。纯函数：相同输入必然得到相同输出（含题目顺序、种子、指纹输入）。
     */
    public static ComposeResult compose(List<MaterialSnapshot> pool, List<Quota> quotas,
                                        PickParams params, long baseSeed, int paperCount) {
        ComposeResult result = new ComposeResult();
        result.requestedCount = paperCount;

        // 素材按 code 排序，桶内候选顺序确定
        List<MaterialSnapshot> orderedPool = new ArrayList<>(pool);
        orderedPool.sort((a, b) -> a.code.compareTo(b.code));
        Map<String, List<MaterialSnapshot>> buckets = new LinkedHashMap<>();
        for (Quota q : quotas) {
            buckets.put(q.key(), new ArrayList<>());
        }
        for (MaterialSnapshot m : orderedPool) {
            String key = m.knowledgePoint + "|" + m.difficulty;
            if (buckets.containsKey(key)) {
                buckets.get(key).add(m);
            }
        }

        int paperSize = 0;
        for (Quota q : quotas) {
            paperSize += q.count;
        }

        // 单套可行性硬校验：任一桶可用不足，任何一套都组不出来
        for (Quota q : quotas) {
            int available = buckets.get(q.key()).size();
            if (available < q.count) {
                result.shortages.add(new Shortage(q.knowledgePoint, q.difficulty, q.count, available));
            }
        }
        if (!result.shortages.isEmpty()) {
            result.feasible = false;
            result.generatedCount = 0;
            result.maxActualOverlapRate = 0d;
            for (Shortage s : result.shortages) {
                result.warnings.add(String.format(
                        "素材池不足：知识点[%s] 难度档[%s] 需 %d 题，可用仅 %d 题",
                        s.knowledgePoint, s.difficulty, s.required, s.available));
            }
            for (int i = 0; i < paperCount; i++) {
                PaperPick fail = new PaperPick();
                fail.paperIndex = i + 1;
                fail.seed = attemptSeed(baseSeed, i, 0);
                fail.maxOverlapRate = 0d;
                fail.accepted = false;
                fail.failReason = "POOL_SHORTAGE";
                result.papers.add(fail);
            }
            return result;
        }

        result.feasible = true;
        int maxTries = params.allowReplacement ? Math.max(1, params.tries) : 1;
        List<PaperPick> accepted = new ArrayList<>();

        for (int i = 0; i < paperCount; i++) {
            PaperPick pick = new PaperPick();
            pick.paperIndex = i + 1;
            boolean resolved = false;

            for (int a = 0; a < maxTries; a++) {
                long seed = attemptSeed(baseSeed, i, a);
                List<MaterialSnapshot> drawn = drawOnce(buckets, quotas, seed);
                List<String> codes = codesOf(drawn);
                double overlap = maxOverlap(drawn, accepted, paperSize);

                if (overlap <= params.maxOverlapRate + 1e-12) {
                    pick.seed = seed;
                    pick.attemptNo = a;
                    pick.items = drawn;
                    pick.maxOverlapRate = overlap;
                    pick.accepted = true;
                    result.attempts.add(new AttemptTrace(i + 1, a, seed, codes, overlap, "ACCEPTED"));
                    resolved = true;
                    break;
                }
                result.attempts.add(new AttemptTrace(i + 1, a, seed, codes, overlap, "REJECTED_OVERLAP"));
            }

            if (resolved) {
                accepted.add(pick);
            } else {
                pick.seed = attemptSeed(baseSeed, i, maxTries - 1);
                pick.accepted = false;
                pick.failReason = "OVERLAP_UNRESOLVED";
                result.warnings.add(String.format(
                        "第 %d 套经 %d 轮试抽仍无法把套间重合率压到 %.2f%% 以内，请扩充素材池或放宽阈值",
                        i + 1, maxTries, params.maxOverlapRate * 100));
            }
            result.papers.add(pick);
        }

        result.generatedCount = accepted.size();
        double maxRate = 0d;
        for (PaperPick p : accepted) {
            maxRate = Math.max(maxRate, p.maxOverlapRate);
        }
        result.maxActualOverlapRate = maxRate;
        return result;
    }

    /**
     * 按指定试抽种子复算单套选题（公平性验证用）：与组卷时该轮逐桶洗牌取题完全一致。
     * 输入池快照/配比必须用组卷时刻冻结值，不读当前素材池。
     */
    public static List<MaterialSnapshot> drawFromSeed(List<MaterialSnapshot> pool, List<Quota> quotas, long seed) {
        List<MaterialSnapshot> orderedPool = new ArrayList<>(pool);
        orderedPool.sort((a, b) -> a.code.compareTo(b.code));
        Map<String, List<MaterialSnapshot>> buckets = new LinkedHashMap<>();
        for (Quota q : quotas) {
            buckets.put(q.key(), new ArrayList<>());
        }
        for (MaterialSnapshot m : orderedPool) {
            String key = m.knowledgePoint + "|" + m.difficulty;
            if (buckets.containsKey(key)) {
                buckets.get(key).add(m);
            }
        }
        return drawOnce(buckets, quotas, seed);
    }

    /** 单轮：按配比顺序逐桶洗牌取题，桶间共用同一 Random（序列确定）。 */
    private static List<MaterialSnapshot> drawOnce(Map<String, List<MaterialSnapshot>> buckets,
                                                   List<Quota> quotas, long seed) {
        Random random = new Random(seed);
        List<MaterialSnapshot> picked = new ArrayList<>();
        for (Quota q : quotas) {
            List<MaterialSnapshot> candidates = new ArrayList<>(buckets.get(q.key()));
            Collections.shuffle(candidates, random);
            for (int k = 0; k < q.count; k++) {
                picked.add(candidates.get(k));
            }
        }
        return picked;
    }

    /** 与每个已采纳套的重合率最大值：共享题数 / 单套题量。 */
    private static double maxOverlap(List<MaterialSnapshot> drawn, List<PaperPick> accepted, int paperSize) {
        if (accepted.isEmpty() || paperSize == 0) {
            return 0d;
        }
        java.util.Set<String> mine = new java.util.HashSet<>();
        for (MaterialSnapshot m : drawn) {
            mine.add(m.code);
        }
        double max = 0d;
        for (PaperPick other : accepted) {
            int shared = 0;
            for (MaterialSnapshot m : other.items) {
                if (mine.contains(m.code)) {
                    shared++;
                }
            }
            max = Math.max(max, (double) shared / paperSize);
        }
        return max;
    }

    private static List<String> codesOf(List<MaterialSnapshot> items) {
        List<String> codes = new ArrayList<>();
        for (MaterialSnapshot m : items) {
            codes.add(m.code);
        }
        return codes;
    }

    private static long attemptSeed(long baseSeed, int paperIndex, int attempt) {
        return baseSeed + GOLDEN1 * (paperIndex + 1L) + GOLDEN2 * (attempt + 1L);
    }
}
