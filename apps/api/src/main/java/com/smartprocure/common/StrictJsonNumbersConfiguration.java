package com.smartprocure.common;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI integer properties accept JSON integers, preserving exact fen and version inputs. */
@Configuration
public class StrictJsonNumbersConfiguration {
    @Bean Jackson2ObjectMapperBuilderCustomizer strictIntegerInputs() {
        return builder->builder.postConfigurer(mapper->mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean,CoercionAction.Fail));
    }
}
