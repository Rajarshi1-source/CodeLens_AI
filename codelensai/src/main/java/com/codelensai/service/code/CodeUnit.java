package com.codelensai.service.code;

import java.util.Set;

/**
 * A structural unit of source code (class, method, function, or a fixed window when the language is not
 * understood). Line numbers are 1-based and inclusive. {@code calledNames} / {@code declaredNames} feed
 * the G1 symbol index (who calls what).
 */
public record CodeUnit(String name, Kind kind, int startLine, int endLine, String signature,
                       Set<String> calledNames, Set<String> declaredNames) {

    public enum Kind { CLASS, METHOD, FUNCTION, BLOCK, WINDOW }

    public CodeUnit {
        calledNames = calledNames == null ? Set.of() : Set.copyOf(calledNames);
        declaredNames = declaredNames == null ? Set.of() : Set.copyOf(declaredNames);
        if (endLine < startLine) {
            endLine = startLine;
        }
    }

    public boolean contains(int line) {
        return line >= startLine && line <= endLine;
    }

    public int length() {
        return endLine - startLine + 1;
    }

    public boolean isCallable() {
        return kind == Kind.METHOD || kind == Kind.FUNCTION;
    }
}
