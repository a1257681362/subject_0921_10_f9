package com.evops.papercomp.controller;

import com.evops.common.ApiResponse;
import com.evops.papercomp.dto.ComposeRequest;
import com.evops.papercomp.dto.PaperVoidRequest;
import com.evops.papercomp.entity.ComposeAttempt;
import com.evops.papercomp.entity.ComposeBatch;
import com.evops.papercomp.entity.Paper;
import com.evops.papercomp.entity.PaperVoidRecord;
import com.evops.papercomp.service.ComposeService;
import com.evops.papercomp.vo.ComposePreview;
import com.evops.papercomp.vo.ComposeResultView;
import com.evops.papercomp.vo.PaperVerifyView;
import com.evops.papercomp.vo.PaperView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 多场并行组卷：预检 → 执行；卷面指纹备查、按冻结快照复算验证公平；卷面只读、废弃留痕。
 */
@RestController
@RequestMapping("/api/paper-compose")
public class ComposeController {

    private final ComposeService composeService;

    public ComposeController(ComposeService composeService) {
        this.composeService = composeService;
    }

    /** 预检：配比可用性（池不足逐桶提示）、拟用版本/种子/阈值，并试算重合率与重抽（不落库）。 */
    @PostMapping("/preview")
    public ApiResponse<ComposePreview> preview(@Valid @RequestBody ComposeRequest request) {
        return ApiResponse.ok(composeService.preview(request));
    }

    /** 执行：多套并行抽题，落组卷批次、卷面、题目明细、指纹与试抽痕迹。 */
    @PostMapping
    public ApiResponse<ComposeResultView> compose(@Valid @RequestBody ComposeRequest request) {
        return ApiResponse.ok(composeService.compose(request));
    }

    @GetMapping("/batches")
    public ApiResponse<List<ComposeBatch>> listBatches(@RequestParam(required = false) String projectCode,
                                                       @RequestParam(required = false) Long examBatchId) {
        return ApiResponse.ok(composeService.listBatches(projectCode, examBatchId));
    }

    /** 组卷批次详情（含配比，不含池快照大字段）。 */
    @GetMapping("/batches/{id}")
    public ApiResponse<ComposeBatch> getBatch(@PathVariable Long id) {
        ComposeBatch batch = composeService.requireBatch(id);
        batch.setPoolSnapshot(null);
        return ApiResponse.ok(batch);
    }

    @GetMapping("/batches/{id}/papers")
    public ApiResponse<List<PaperView>> papers(@PathVariable Long id) {
        return ApiResponse.ok(composeService.papersOfBatch(id));
    }

    /** 试抽痕迹：含因重合率超阈被否的每一轮（自动重抽可查）。 */
    @GetMapping("/batches/{id}/attempts")
    public ApiResponse<List<ComposeAttempt>> attempts(@PathVariable Long id) {
        return ApiResponse.ok(composeService.attempts(id));
    }

    /** 卷面详情（含题目明细与冻结属性）。 */
    @GetMapping("/papers/{id}")
    public ApiResponse<PaperView> paper(@PathVariable Long id) {
        return ApiResponse.ok(composeService.getPaperView(id));
    }

    /** 公平性复算：按组卷时刻冻结的池快照+种子重放，校验入选题与指纹。 */
    @GetMapping("/papers/{id}/verify")
    public ApiResponse<PaperVerifyView> verify(@PathVariable Long id) {
        return ApiResponse.ok(composeService.verify(id));
    }

    /** 废弃卷面（只读纠错：留废弃记录，不删除；需要新卷另行组卷）。 */
    @PostMapping("/papers/{id}/void")
    public ApiResponse<Paper> voidPaper(@PathVariable Long id,
                                        @Valid @RequestBody PaperVoidRequest request) {
        return ApiResponse.ok(composeService.voidPaper(id, request.getReason()));
    }

    @GetMapping("/papers/{id}/void-records")
    public ApiResponse<List<PaperVoidRecord>> voidRecords(@PathVariable Long id) {
        return ApiResponse.ok(composeService.voidRecords(id));
    }
}
