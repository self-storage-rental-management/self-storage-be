package com.storagehub.validation;

import jakarta.validation.*;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidCccd.Validator.class)
public @interface ValidCccd {
    String message() default "CCCD không hợp lệ";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    class Validator implements ConstraintValidator<ValidCccd, String> {
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null || value.isBlank()) return true;
            String error = CccdPolicy.error(value);
            if (error == null) return true;
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(error).addConstraintViolation();
            return false;
        }
    }
}
