package com.niranjankagri.aitesting.framework;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class that Claude drafted and a person reviewed before it was merged. The approve
 * command adds it; the README's rule is that no AI draft enters src/test without a named reviewer.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AiDrafted {

    /** Model that wrote the draft, for example "claude-opus-5-5". */
    String model();

    /** Person who reviewed and approved the draft. */
    String reviewedBy();

    /** Approval date, ISO format. */
    String reviewedOn();
}
