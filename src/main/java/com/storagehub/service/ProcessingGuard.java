package com.storagehub.service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Method arguments must begin with ActorPrincipal and the target UUID. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ProcessingGuard {
    ProcessingLeaseService.Target value();
}
