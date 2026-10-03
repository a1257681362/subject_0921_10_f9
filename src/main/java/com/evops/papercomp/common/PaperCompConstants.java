package com.evops.papercomp.common;

/**
 * 多场并行组卷业务常量。
 */
public final class PaperCompConstants {

    private PaperCompConstants() {
    }

    /** 默认项目编码（组卷未指定项目时）。 */
    public static final String PROJECT_DEFAULT = "DEFAULT";

    // 抽选算法版本状态（与混编/等级/证书规则同一套三段式）
    public static final String VERSION_DRAFT = "DRAFT";
    public static final String VERSION_ACTIVE = "ACTIVE";
    public static final String VERSION_DISABLED = "DISABLED";

    // 抽选策略
    /** 知识点×难度档配额：每个 (知识点,难度档) 桶内随机抽 count 题。 */
    public static final String STRATEGY_KP_DIFFICULTY_QUOTA = "KP_DIFFICULTY_QUOTA";

    // 素材状态
    public static final String MATERIAL_ACTIVE = "ACTIVE";
    public static final String MATERIAL_DISABLED = "DISABLED";

    // 素材池变更类型
    public static final String CHANGE_ADD = "ADD";
    public static final String CHANGE_UPDATE = "UPDATE";
    public static final String CHANGE_DISABLE = "DISABLE";
    public static final String CHANGE_ENABLE = "ENABLE";

    // 组卷批次状态
    /** 全部套数生成成功。 */
    public static final String BATCH_COMPOSED = "COMPOSED";
    /** 池子不足，只生成了部分套（需补充素材后对缺套重出）。 */
    public static final String BATCH_PARTIAL = "PARTIAL";

    // 卷面状态
    public static final String PAPER_ACTIVE = "ACTIVE";
    public static final String PAPER_VOID = "VOID";

    // 试抽结果
    public static final String ATTEMPT_ACCEPTED = "ACCEPTED";
    public static final String ATTEMPT_REJECTED_OVERLAP = "REJECTED_OVERLAP";
    public static final String ATTEMPT_REJECTED_POOL = "REJECTED_POOL";

    // 内置默认抽选参数（项目尚无生效算法版本时的兜底口径）
    public static final double DEFAULT_MAX_OVERLAP_RATE = 0.20d;
    public static final int DEFAULT_TRIES = 200;
    public static final boolean DEFAULT_ALLOW_REPLACEMENT = true;

    public static final int MIN_TRIES = 1;
    public static final int MAX_TRIES = 10000;

    /** 重合率口径：两套共享题数 / 单套题量（每套等长，即 Jaccard 分子/每套长度）。 */
    public static final String OVERLAP_BASIS = "INTERSECTION_PER_PAPER_SIZE";
}
