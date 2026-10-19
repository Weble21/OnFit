package com.donggeon.jobrecommendation.jobanalysis;

/** A failed AI service call. The code is safe to log and store; it never carries posting or profile text. */
public class JobAiException extends RuntimeException {
    private final String code;

    public JobAiException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() { return code; }
}
