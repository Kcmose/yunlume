package com.example.nav.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = SafeUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeUrl {

    String message() default "地址必须是安全的 HTTP(S) 地址或站内绝对路径";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    boolean allowInternal() default true;

    boolean allowBlank() default true;
}
