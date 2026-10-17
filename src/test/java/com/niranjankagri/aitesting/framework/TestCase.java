package com.niranjankagri.aitesting.framework;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Links a test method to its test case in {@code test-cases/} (the way a TargetProcess or Jira test
 * case id would). The evidence listener and the triage report show it next to the failure.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TestCase {

    /** Test case id, for example "TC-103". */
    String value();
}
