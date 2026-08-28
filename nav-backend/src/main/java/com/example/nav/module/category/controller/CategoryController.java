package com.example.nav.module.category.controller;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.dto.VisibilityDTO;
import com.example.nav.common.result.Result;
import com.example.nav.module.category.dto.CategoryCreateDTO;
import com.example.nav.module.category.dto.CategoryUpdateDTO;
import com.example.nav.module.category.service.CategoryService;
import com.example.nav.module.category.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/admin/categories")
@SecurityRequirement(name = "bearerAuth")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(summary = "分类列表")
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.listAll());
    }

    @PostMapping
    @Operation(summary = "新增分类")
    public Result<CategoryVO> create(@Valid @RequestBody CategoryCreateDTO createDTO) {
        return Result.success(categoryService.create(createDTO));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新分类")
    public Result<CategoryVO> update(@PathVariable Long id, @Valid @RequestBody CategoryUpdateDTO updateDTO) {
        return Result.success(categoryService.update(id, updateDTO));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除分类")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/visible")
    @Operation(summary = "设置分类显隐")
    public Result<CategoryVO> visible(@PathVariable Long id, @Valid @RequestBody VisibilityDTO visibilityDTO) {
        return Result.success(categoryService.setVisible(id, visibilityDTO.visible()));
    }

    @PutMapping("/sort")
    @Operation(summary = "批量调整分类排序")
    public Result<List<CategoryVO>> sort(
            @NotEmpty(message = "排序列表不能为空")
            @Size(max = 1000, message = "排序列表不能超过 1000 项")
            @RequestBody List<@Valid SortItemDTO> items
    ) {
        return Result.success(categoryService.sort(items));
    }
}
