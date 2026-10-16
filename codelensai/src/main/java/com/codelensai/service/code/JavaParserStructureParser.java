package com.codelensai.service.code;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Exact Java structure via JavaParser: class/record/enum/interface ranges plus every method and
 * constructor, with the names each one calls. Falls back to {@link BraceStructureParser} when the file
 * does not parse (e.g. a partial snippet).
 */
public class JavaParserStructureParser implements CodeStructureParser {

    private final JavaParser parser;
    private final BraceStructureParser fallback = new BraceStructureParser();

    public JavaParserStructureParser() {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
        this.parser = new JavaParser(config);
    }

    @Override
    public boolean supports(String language) {
        return "java".equals(language);
    }

    @Override
    public List<CodeUnit> parse(String path, String source) {
        ParseResult<CompilationUnit> result = parser.parse(source == null ? "" : source);
        Optional<CompilationUnit> cu = result.getResult();
        if (!result.isSuccessful() || cu.isEmpty()) {
            return fallback.parse(path, source);
        }
        List<CodeUnit> units = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.get().findAll(TypeDeclaration.class)) {
            range(type).ifPresent(r -> {
                Set<String> declared = new LinkedHashSet<>();
                declared.add(type.getNameAsString());
                for (FieldDeclaration f : type.getFields()) {
                    f.getVariables().forEach(v -> declared.add(v.getNameAsString()));
                }
                units.add(new CodeUnit(type.getNameAsString(), CodeUnit.Kind.CLASS, r[0], r[1],
                        type.getNameAsString(), Set.of(), declared));
            });
        }
        for (CallableDeclaration<?> callable : cu.get().findAll(CallableDeclaration.class)) {
            range(callable).ifPresent(r -> units.add(new CodeUnit(
                    callable.getNameAsString(),
                    CodeUnit.Kind.METHOD,
                    r[0], r[1],
                    callable.getDeclarationAsString(false, false, true),
                    calledNames(callable),
                    declaredNames(callable))));
        }
        units.sort(Comparator.comparingInt(CodeUnit::startLine).thenComparing(u -> -u.length()));
        return units;
    }

    private static Set<String> calledNames(Node node) {
        Set<String> names = new LinkedHashSet<>();
        node.findAll(MethodCallExpr.class).forEach(m -> names.add(m.getNameAsString()));
        node.findAll(ObjectCreationExpr.class).forEach(o -> names.add(o.getType().getNameAsString()));
        return names;
    }

    private static Set<String> declaredNames(CallableDeclaration<?> callable) {
        Set<String> names = new LinkedHashSet<>();
        names.add(callable.getNameAsString());
        if (callable instanceof MethodDeclaration || callable instanceof ConstructorDeclaration) {
            callable.findAll(VariableDeclarator.class).forEach(v -> names.add(v.getNameAsString()));
        }
        return names;
    }

    private static Optional<int[]> range(Node node) {
        return node.getRange().map(r -> new int[]{r.begin.line, r.end.line});
    }
}
