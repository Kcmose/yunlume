package com.example.nav.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class SafeUrlValidator implements ConstraintValidator<SafeUrl, String> {

    private boolean allowInternal;
    private boolean allowBlank;

    @Override
    public void initialize(SafeUrl annotation) {
        allowInternal = annotation.allowInternal();
        allowBlank = annotation.allowBlank();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return allowBlank;
        return allowInternal
                ? SafeUrlRules.isSafeHttpOrInternal(value)
                : SafeUrlRules.isSafeHttp(value);
    }
}
