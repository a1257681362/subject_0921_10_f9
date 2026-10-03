package com.evops.papercomp.controller;

import com.evops.common.ApiResponse;
import com.evops.papercomp.dto.MaterialRequest;
import com.evops.papercomp.dto.MaterialUpdateRequest;
import com.evops.papercomp.entity.Material;
import com.evops.papercomp.entity.MaterialChange;
import com.evops.papercomp.service.MaterialService;
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
 * 素材池题目（增删留痕）：新增 / 修改 / 停用 / 启用，不做物理删除；历史卷面靠冻结快照不受影响。
 */
@RestController
@RequestMapping("/api/pc-materials")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @PostMapping
    public ApiResponse<Material> create(@Valid @RequestBody MaterialRequest request) {
        return ApiResponse.ok(materialService.create(request));
    }

    /** 修改关键属性（逐字段前后值留痕）。 */
    @PostMapping("/{id}/update")
    public ApiResponse<Material> update(@PathVariable Long id,
                                        @RequestBody MaterialUpdateRequest request) {
        return ApiResponse.ok(materialService.update(id, request));
    }

    /** 停用（不物理删除；组卷不再抽，历史卷面保留）。 */
    @PostMapping("/{id}/disable")
    public ApiResponse<Material> disable(@PathVariable Long id,
                                         @RequestParam(required = false) String reason) {
        return ApiResponse.ok(materialService.disable(id, reason));
    }

    /** 重新启用。 */
    @PostMapping("/{id}/enable")
    public ApiResponse<Material> enable(@PathVariable Long id,
                                        @RequestParam(required = false) String reason) {
        return ApiResponse.ok(materialService.enable(id, reason));
    }

    @GetMapping
    public ApiResponse<List<Material>> list(@RequestParam(required = false) String projectCode,
                                            @RequestParam(required = false) String knowledgePoint,
                                            @RequestParam(required = false) String difficulty,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Boolean includeDisabled) {
        return ApiResponse.ok(materialService.list(projectCode, knowledgePoint, difficulty,
                status, includeDisabled));
    }

    @GetMapping("/{id}")
    public ApiResponse<Material> get(@PathVariable Long id) {
        return ApiResponse.ok(materialService.get(id));
    }

    /** 素材池增删改留痕。 */
    @GetMapping("/{id}/changes")
    public ApiResponse<List<MaterialChange>> changes(@PathVariable Long id) {
        return ApiResponse.ok(materialService.changes(id));
    }
}
