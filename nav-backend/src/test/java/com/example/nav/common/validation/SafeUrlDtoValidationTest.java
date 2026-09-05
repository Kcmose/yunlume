package com.example.nav.common.validation;

import com.example.nav.module.bookmark.dto.BookmarkCreateDTO;
import com.example.nav.module.site.dto.SiteConfigUpdateDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeUrlDtoValidationTest {

    @Test
    void bookmarkWriteRejectsHttpUserInfo() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            BookmarkCreateDTO dto = new BookmarkCreateDTO(
                    1L, "示例", "https://user:secret@example.com/path", "", "", 0,
                    false, true, true
            );

            assertThat(validator.validate(dto)).extracting(violation -> violation.getPropertyPath().toString())
                    .contains("url");
        }
    }

    @Test
    void siteWriteRejectsUrlsThatPortableImportWouldReject() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            SiteConfigUpdateDTO dto = new SiteConfigUpdateDTO(
                    "yunlume",
                    "导航",
                    "javascript:alert(1)",
                    "color",
                    "#000000",
                    "https://user:secret@example.com/desktop.png",
                    "https://user:secret@example.com/mobile.png",
                    "#ffffff",
                    false,
                    false,
                    "https://user@example.com/music.mp3",
                    false,
                    false,
                    "",
                    0
            );

            assertThat(validator.validate(dto)).extracting(violation -> violation.getPropertyPath().toString())
                    .contains("publishUrl", "backgroundImage", "mobileBackgroundImage", "musicUrl");
        }
    }
}
