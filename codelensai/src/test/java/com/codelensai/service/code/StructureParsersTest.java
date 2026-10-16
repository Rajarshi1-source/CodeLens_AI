package com.codelensai.service.code;

import com.codelensai.util.DiffParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureParsersTest {

    private final CodeStructureParsers parsers = new CodeStructureParsers(new DiffParser());

    @Test
    void javaParserFindsExactMethodRangesAndCalls() {
        String src = """
                package a;

                public class Cart {
                    private final PriceCalculator calc = new PriceCalculator();

                    public Receipt checkout(Cart cart) {
                        var total = calc.calculateTotal(cart.items());
                        return new Receipt(total);
                    }

                    int size() { return 1; }
                }
                """;
        List<CodeUnit> units = parsers.parse("src/Cart.java", src);
        CodeUnit cls = units.stream().filter(u -> u.kind() == CodeUnit.Kind.CLASS).findFirst().orElseThrow();
        assertEquals(3, cls.startLine());
        assertEquals(12, cls.endLine());
        CodeUnit checkout = units.stream().filter(u -> u.name().equals("checkout")).findFirst().orElseThrow();
        assertEquals(6, checkout.startLine());
        assertEquals(9, checkout.endLine());
        assertTrue(checkout.calledNames().contains("calculateTotal"));
        assertEquals("checkout", CodeStructureParsers.groupingUnit(units, 7).orElseThrow().name());
    }

    @Test
    void brokenJavaFallsBackToBraceHeuristics() {
        String src = """
                public int broken( {
                    return 1;
                }
                public int ok(int a) {
                    if (a > 0) { return a; }
                    return 0;
                }
                """;
        Optional<CodeUnit> ok = parsers.parse("X.java", src).stream().filter(u -> u.name().equals("ok")).findFirst();
        assertTrue(ok.isPresent());
        assertEquals(4, ok.get().startLine());
        assertEquals(7, ok.get().endLine());
    }

    @Test
    void braceParserHandlesTypeScriptAndStrings() {
        String src = """
                export function total(items: Item[]): number {
                  const label = "not a } brace";
                  return items.reduce((a, b) => a + price(b), 0);
                }

                export const fmt = (n: number) => {
                  return `${n}`;
                };
                """;
        List<CodeUnit> units = parsers.parse("src/total.ts", src);
        CodeUnit total = units.stream().filter(u -> u.name().equals("total")).findFirst().orElseThrow();
        assertEquals(1, total.startLine());
        assertEquals(4, total.endLine());
        assertTrue(total.calledNames().contains("price"));
        assertTrue(units.stream().anyMatch(u -> u.name().equals("fmt") && u.endLine() == 8));
    }

    @Test
    void braceParserHandlesGo() {
        String src = """
                package main

                func (s *Server) Handle(w http.ResponseWriter, r *http.Request) {
                	if r == nil {
                		return
                	}
                	s.serve(w, r)
                }
                """;
        CodeUnit h = parsers.parse("server.go", src).stream().filter(u -> u.name().equals("Handle")).findFirst().orElseThrow();
        assertEquals(3, h.startLine());
        assertEquals(8, h.endLine());
    }

    @Test
    void indentParserHandlesPython() {
        String src = """
                class Repo:
                    def find(self, id):
                        row = self.db.get(id)

                        return row

                def helper():
                    return 1
                """;
        List<CodeUnit> units = parsers.parse("repo.py", src);
        CodeUnit find = units.stream().filter(u -> u.name().equals("find")).findFirst().orElseThrow();
        assertEquals(2, find.startLine());
        assertEquals(5, find.endLine());
        CodeUnit cls = units.stream().filter(u -> u.name().equals("Repo")).findFirst().orElseThrow();
        assertEquals(5, cls.endLine());
        assertEquals(8, units.stream().filter(u -> u.name().equals("helper")).findFirst().orElseThrow().endLine());
    }

    @Test
    void unknownLanguageFallsBackToOverlappingWindows() {
        String src = "x\n".repeat(200);
        List<CodeUnit> windows = parsers.parseOrWindow("notes.txt", src);
        assertTrue(windows.size() >= 3);
        assertEquals(1, windows.get(0).startLine());
        assertEquals(80, windows.get(0).endLine());
        assertEquals(71, windows.get(1).startLine());
    }

    @Test
    void symbolExtractorReadsHunkFuncnameAndDeclarations() {
        var symbols = SymbolExtractor.declaredInHunk("""
                @@ -1,3 +1,3 @@ public class Ledger {
                -    public void post(Entry e) {
                +    public void post(Entry e, Clock clock) {
                """);
        assertTrue(symbols.contains("Ledger"));
        assertTrue(symbols.contains("post"));
    }
}
