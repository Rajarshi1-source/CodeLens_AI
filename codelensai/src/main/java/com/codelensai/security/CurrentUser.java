package com.codelensai.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the logged-in {@link com.codelensai.model.entity.User} into a controller method. Resolution fails
 * with 401 when there is no authenticated session or the user row is missing.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
