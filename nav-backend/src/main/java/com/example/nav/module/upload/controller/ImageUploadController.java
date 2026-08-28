package com.example.nav.module.upload.controller;

import com.example.nav.common.result.Result;
import com.example.nav.module.upload.service.ImageUploadService;
import com.example.nav.module.upload.vo.ImageUploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/upload")
@SecurityRequirement(name = "bearerAuth")
public class ImageUploadController {

    private final ImageUploadService imageUploadService;

    public ImageUploadController(ImageUploadService imageUploadService) {
        this.imageUploadService = imageUploadService;
    }

    @PostMapping("/image")
    @Operation(summary = "上传站点背景图片")
    public Result<ImageUploadVO> uploadImage(@RequestPart("file") MultipartFile file) {
        return Result.success(imageUploadService.upload(file));
    }
}
