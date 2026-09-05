package com.example.nav.module.datapackage.controller;

import com.example.nav.common.result.Result;
import com.example.nav.module.datapackage.model.PortablePackageModels.ConfirmResponse;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobResponse;
import com.example.nav.module.datapackage.model.PortablePackageModels.PreviewResponse;
import com.example.nav.module.datapackage.service.BookmarkMarkdownExportService;
import com.example.nav.module.datapackage.service.BookmarkMarkdownExportService.ExportedMarkdown;
import com.example.nav.module.datapackage.service.PortableDataPackageService;
import com.example.nav.module.datapackage.service.PortablePackageWriter.ExportedPackage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/admin/data")
@SecurityRequirement(name = "bearerAuth")
public class PortableDataPackageController {

    private final PortableDataPackageService dataPackageService;
    private final BookmarkMarkdownExportService bookmarkMarkdownExportService;

    public PortableDataPackageController(
            PortableDataPackageService dataPackageService,
            BookmarkMarkdownExportService bookmarkMarkdownExportService
    ) {
        this.dataPackageService = dataPackageService;
        this.bookmarkMarkdownExportService = bookmarkMarkdownExportService;
    }

    @GetMapping(value = "/export", produces = "application/zip")
    @Operation(summary = "导出可移植数据包")
    public ResponseEntity<byte[]> exportPackage() {
        ExportedPackage exported = dataPackageService.exportPackage();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(exported.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(exported.bytes().length)
                .body(exported.bytes());
    }

    @GetMapping(value = "/bookmarks/markdown", produces = "text/markdown;charset=UTF-8")
    @Operation(summary = "导出人类可读的书签 Markdown 备份")
    public ResponseEntity<byte[]> exportBookmarksMarkdown() {
        ExportedMarkdown exported = bookmarkMarkdownExportService.export();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(exported.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .contentLength(exported.bytes().length)
                .body(exported.bytes());
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "预检可移植数据包（不会写入数据库）")
    public Result<PreviewResponse> preview(
            @RequestPart("file") MultipartFile file,
            Authentication authentication
    ) {
        return Result.success(dataPackageService.preview(file, authentication));
    }

    @PostMapping("/import/{previewToken}/confirm")
    @Operation(summary = "确认并异步导入已预检的数据包")
    public Result<ConfirmResponse> confirm(
            @PathVariable String previewToken,
            Authentication authentication
    ) {
        return Result.success(dataPackageService.confirm(previewToken, authentication));
    }

    @GetMapping("/import/jobs/{jobId}")
    @Operation(summary = "查询可移植数据包导入任务")
    public Result<JobResponse> job(
            @PathVariable String jobId,
            Authentication authentication
    ) {
        return Result.success(dataPackageService.job(jobId, authentication));
    }

    @GetMapping("/import/jobs/current")
    @Operation(summary = "查询当前管理员最近的可恢复导入任务")
    public Result<JobResponse> currentJob(Authentication authentication) {
        return Result.success(dataPackageService.currentJob(authentication));
    }
}
