package com.example.nav.common.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtPropertiesTest {

    @Test
    void expirationMustStayWithinFiveMinutesAndSevenDays() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            JwtProperties properties = new JwtProperties();

            properties.setExpirationMinutes(4);
            assertTrue(validator.validate(properties).stream()
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("expirationMinutes")));

            properties.setExpirationMinutes(10081);
            assertTrue(validator.validate(properties).stream()
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("expirationMinutes")));

            properties.setExpirationMinutes(5);
            assertEquals(0, validator.validate(properties).size());

            properties.setExpirationMinutes(10080);
            assertEquals(0, validator.validate(properties).size());
        }
    }
}
